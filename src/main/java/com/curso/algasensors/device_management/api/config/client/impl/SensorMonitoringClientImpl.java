package com.curso.algasensors.device_management.api.config.client.impl;

import com.curso.algasensors.device_management.api.config.client.SensorMonitoringClient;
import com.curso.algasensors.device_management.api.config.client.SensorMonitoringClientBadGatewayException;
import io.hypersistence.tsid.TSID;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SensorMonitoringClientImpl implements SensorMonitoringClient {
    private final RestClient restClient;

    public SensorMonitoringClientImpl(RestClient.Builder builder) {
        this.restClient = builder.baseUrl("http://localhost:8082")
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new SensorMonitoringClientBadGatewayException();
                })
                .build();
    }

    @Override
    public void enableMonitoring(TSID sensorId) {
        restClient.put()
                .uri("/api/v1/sensors/{sensorId}/monitoring/enable", sensorId)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void disableMonitoring(TSID sensorId) {
        restClient.delete()
                .uri("/api/v1/sensors/{sensorId}/monitoring/enable", sensorId)
                .retrieve()
                .toBodilessEntity();
    }
}
