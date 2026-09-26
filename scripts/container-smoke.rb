#!/usr/bin/env ruby
# Mutates only an explicitly acknowledged disposable Compose demo/test stack.
require "net/http"
require "json"
require "securerandom"
require "open3"
require "base64"
abort "Set SMOKE_DISPOSABLE=true; this creates users, stock, an order and a payment." unless ENV["SMOKE_DISPOSABLE"] == "true"
COMPOSE = ["docker", "compose", "-f", "docker-compose.dev.yml", "-f", "docker-compose.runtime.yml"].freeze
def sql(service, user, database, statement)
  out, status = Open3.capture2(*COMPOSE, "exec", "-T", service, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", database, "-Atc", statement)
  raise "database check failed: #{service}" unless status.success?
  out.strip
end
def http(method, path, data = nil, headers = {}, port = 8080)
  uri = URI("http://127.0.0.1:#{port}#{path}")
  request = Net::HTTP.const_get(method).new(uri)
  headers.each { |k, v| request[k] = v }
  if data
    request["Content-Type"] = "application/json"
    request.body = JSON.generate(data)
  end
  response = Net::HTTP.start(uri.host, uri.port, open_timeout: 3, read_timeout: 10) { |client| client.request(request) }
  raise "#{method} #{path}: HTTP #{response.code}" unless response.code.to_i.between?(200, 299)
  response.body.empty? ? nil : JSON.parse(response.body)
end
def uuid(value)
  raise "invalid response UUID" unless value.is_a?(String) && value.match?(/\A[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\z/)
  value
end
def eventually
  deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + 60
  loop do
    return if yield
    raise "timed out waiting for durable completion" if Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline
    sleep 1
  end
end
[8080, 8082, 8083].each { |port| raise "not ready" unless http("Get", "/actuator/health/readiness", nil, {}, port) == {"status" => "UP"} }
password = SecureRandom.hex(24)
email = "smoke-#{SecureRandom.uuid}@example.test"
owner = uuid(http("Post", "/api/auth/register", {email: email, password: password}).fetch("id"))
# The only setup bypass: promote this freshly generated test user to create a catalog fixture.
sql("postgres", "platform", "order_platform", "UPDATE users SET role='ADMIN' WHERE id='#{owner}'")
login = {email: email, password: password}
admin = {"Authorization" => "Bearer " + http("Post", "/api/auth/login", login).fetch("accessToken")}
product = uuid(http("Post", "/api/admin/products", {sku: "SMOKE-#{SecureRandom.uuid}", name: "Smoke item", price: 2.5, currency: "USD"}, admin).fetch("id"))
sql("postgres", "platform", "order_platform", "UPDATE users SET role='CUSTOMER' WHERE id='#{owner}'")
customer = {"Authorization" => "Bearer " + http("Post", "/api/auth/login", login).fetch("accessToken"), "X-Customer-Id" => owner}
inventory = {"Authorization" => "Basic " + Base64.strict_encode64("core:local-inventory-only")}
http("Put", "/internal/v1/stock/#{product}", {quantity: 5}, inventory, 8082)
http("Post", "/api/cart/items", {productId: product, quantity: 2}, customer)
checkout = customer.merge("Idempotency-Key" => SecureRandom.uuid)
order = http("Post", "/api/orders", nil, checkout)
order_id = uuid(order.fetch("id"))
raise "checkout replay changed" unless http("Post", "/api/orders", nil, checkout) == order
number = order.fetch("orderNumber")
raise "invalid order number" unless number.match?(/\A[A-Za-z0-9-]+\z/)
eventually { http("Post", "/api/orders/#{number}/payment", nil, customer).fetch("orderStatus") == "CONFIRMED" }
http("Post", "/api/orders/#{number}/payment", nil, customer)
eventually { sql("notification-db", "notification", "notification", "SELECT count(*) FROM notification_receipts WHERE order_id='#{order_id}'") == "1" }
raise "stock invariant" unless sql("inventory-db", "inventory", "inventory", "SELECT available_quantity || ':' || reserved_quantity FROM inventory WHERE product_id='#{product}'") == "3:0"
raise "duplicate charge" unless sql("gateway-db", "gateway", "gateway", "SELECT count(*) FROM mock_gateway_charges WHERE idempotency_key='order:#{order_id}'") == "1"
eventually { sql("postgres", "platform", "order_platform", "SELECT count(*) FROM outbox_events WHERE aggregate_id='#{order_id}' AND status='PUBLISHED'") == "1" }
puts "PASS: authenticated checkout/replay, HTTP inventory/payment, confirmed order, Kafka outbox/receipt, stock and one-charge invariants. Order #{number}"
