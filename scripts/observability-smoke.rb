#!/usr/bin/env ruby
# Read-only traffic and dashboard/data-source checks against an already-running local stack.
require "net/http"
require "json"
require "uri"
base = ENV.fetch("PLATFORM_URL", "http://localhost:8080")
prometheus = ENV.fetch("PROMETHEUS_URL", "http://localhost:9090")
grafana = ENV.fetch("GRAFANA_URL", "http://localhost:3000")
def get(url, credentials = nil)
  uri = URI(url)
  request = Net::HTTP::Get.new(uri)
  request.basic_auth(*credentials) if credentials
  result = Net::HTTP.start(uri.host, uri.port, use_ssl: uri.scheme == "https", open_timeout: 3, read_timeout: 10) { |http| http.request(request) }
  raise "#{uri.path}: HTTP #{result.code}" unless result.code == "200"
  result.body
end
5.times do
  20.times { get(base + "/api/products") }
  sleep 5
end
credentials = [ENV.fetch("GRAFANA_USER", "admin"), ENV.fetch("GRAFANA_PASSWORD", "local-grafana-only")]
dashboard = JSON.parse(get(grafana + "/api/dashboards/uid/platform-overview", credentials)).fetch("dashboard")
raise "wrong dashboard" unless dashboard.fetch("panels").size == 18
dashboard.fetch("panels").each do |panel|
  panel.fetch("targets").each do |target|
    response = JSON.parse(get(prometheus + "/api/v1/query?" + URI.encode_www_form(query: target.fetch("expr"))))
    raise "query failed: #{panel['title']}" unless response["status"] == "success"
  end
end
["http_server_requests_seconds_count", "jvm_memory_used_bytes", "hikaricp_connections_active"].each do |metric|
  response = JSON.parse(get(prometheus + "/api/v1/query?" + URI.encode_www_form(query: metric)))
  raise "missing generated-load metric #{metric}" if response.dig("data", "result").empty?
end
health = JSON.parse(get(grafana + "/api/datasources/uid/platform-prometheus/health", credentials))
raise "Grafana datasource unhealthy" unless health["status"] == "OK"
puts "PASS: 100 catalog requests; provisioned 18-panel dashboard; all PromQL queries accepted; live HTTP/JVM/pool metrics; Grafana Prometheus datasource healthy."
