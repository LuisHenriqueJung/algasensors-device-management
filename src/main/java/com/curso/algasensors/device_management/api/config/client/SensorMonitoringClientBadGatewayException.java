package com.curso.algasensors.device_management.api.config.client;


import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class SensorMonitoringClientBadGatewayException extends RuntimeException {
}