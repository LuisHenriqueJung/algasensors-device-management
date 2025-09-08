package com.curso.algasensors.device_management.api.controller;

import com.curso.algasensors.device_management.api.model.SensorInput;
import com.curso.algasensors.device_management.commom.IdGenerator;
import com.curso.algasensors.device_management.domain.model.Sensor;
import com.curso.algasensors.device_management.domain.model.SensorId;
import com.curso.algasensors.device_management.domain.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sensors")
@RequiredArgsConstructor
public class SensorsController {


    private final SensorRepository repository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Sensor create(@RequestBody SensorInput input) {
        Sensor sensor = Sensor.builder()
                .id(new SensorId(IdGenerator.generateTSID()))
                .name(input.getName())
                .model(input.getModel())
                .location(input.getLocation())
                .ip(input.getIp())
                .protocol(input.getProtocol())
                .enabled(false)
                .build();
        return repository.saveAndFlush(sensor);
    }
}
