package com.curso.algasensors.device_management.api.controller;

import com.curso.algasensors.device_management.commom.IdGenerator;
import com.curso.algasensors.device_management.domain.model.Sensor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sensors")
public class SensorsController {

    @PostMapping
    Sensor create(@RequestBody Sensor sensor) {
        return Sensor.builder()
                .id(IdGenerator.generateTSID())
                .name(sensor.getName())
                .model(sensor.getModel())
                .location(sensor.getLocation())
                .ip(sensor.getIp())
                .protocol(sensor.getProtocol())
                .enabled(false)
                .build();
    }
}
