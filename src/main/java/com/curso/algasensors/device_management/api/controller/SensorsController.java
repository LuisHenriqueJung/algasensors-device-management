package com.curso.algasensors.device_management.api.controller;

import com.curso.algasensors.device_management.api.model.SensorInput;
import com.curso.algasensors.device_management.api.model.SensorOutput;
import com.curso.algasensors.device_management.commom.IdGenerator;
import com.curso.algasensors.device_management.domain.model.Sensor;
import com.curso.algasensors.device_management.domain.model.SensorId;
import com.curso.algasensors.device_management.domain.repository.SensorRepository;
import io.hypersistence.tsid.TSID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/sensors")
@RequiredArgsConstructor
public class SensorsController {


    private final SensorRepository repository;

    @GetMapping
    public Page<SensorOutput> search(@PageableDefault Pageable pageable) {
        Page<Sensor> sensors = repository.findAll(pageable);
        return sensors.map(this::convertToOutput);
    }
    
    @GetMapping("{sensorId}")
    SensorOutput get(@PathVariable("sensorId") TSID sensorId) {
        Sensor sensor = repository.findById(new SensorId(sensorId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sensor not found"));
        return convertToOutput(sensor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SensorOutput create(@RequestBody SensorInput input) {
        Sensor sensor = Sensor.builder()
                .id(new SensorId(IdGenerator.generateTSID()))
                .name(input.getName())
                .model(input.getModel())
                .location(input.getLocation())
                .ip(input.getIp())
                .protocol(input.getProtocol())
                .enabled(false)
                .build();
        sensor = repository.saveAndFlush(sensor);
        return convertToOutput(sensor);
    }
    SensorOutput convertToOutput(Sensor sensor) {
        return SensorOutput.builder()
                .id(sensor.getId().getValue())
                .name(sensor.getName())
                .model(sensor.getModel())
                .location(sensor.getLocation())
                .ip(sensor.getIp())
                .protocol(sensor.getProtocol())
                .enabled(sensor.getEnabled())
                .build();
    }
}
