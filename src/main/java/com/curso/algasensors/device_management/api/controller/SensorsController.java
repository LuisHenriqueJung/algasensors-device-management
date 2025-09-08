package com.curso.algasensors.device_management.api.controller;

import com.curso.algasensors.device_management.api.config.client.SensorMonitoringClient;
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

    private static final String SENSOR_NOT_FOUND = "Sensor not found";
    private static final String SENSOR_ALREADY_DISABLED = "Sensor already disabled";

    private final SensorRepository repository;

    private final SensorMonitoringClient sensorMonitoringClient;

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public Page<SensorOutput> search(@PageableDefault Pageable pageable) {
        Page<Sensor> sensors = repository.findAll(pageable);
        return sensors.map(this::convertToOutput);
    }
    
    @GetMapping("{sensorId}")
    @ResponseStatus(HttpStatus.OK)
    SensorOutput get(@PathVariable("sensorId") TSID sensorId) {
        Sensor sensor = repository.findById(new SensorId(sensorId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SENSOR_NOT_FOUND));
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
    @PutMapping("{sensorId}")
    @ResponseStatus(HttpStatus.OK)
    SensorOutput update(@PathVariable("sensorId") TSID sensorId, @RequestBody SensorInput input) {
        Sensor sensor = repository.findById(new SensorId(sensorId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SENSOR_NOT_FOUND));
        sensor.setName(input.getName());
        sensor.setModel(input.getModel());
        sensor.setLocation(input.getLocation());
        sensor.setIp(input.getIp());
        sensor.setProtocol(input.getProtocol());
        sensor = repository.saveAndFlush(sensor);
        return convertToOutput(sensor);
    }

    @PutMapping("{sensorId}/enable")
    @ResponseStatus(HttpStatus.OK)
    void enable(@PathVariable("sensorId") TSID sensorId) {
        Sensor sensor = repository.findById(new SensorId(sensorId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        sensor.setEnabled(true);
        repository.save(sensor);
        sensorMonitoringClient.enableMonitoring(sensorId);
    }

    @DeleteMapping("{sensorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable("sensorId") TSID sensorId) {
        Sensor sensor = repository.findById(new SensorId(sensorId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SENSOR_NOT_FOUND));
        repository.deleteById(sensor.getId());
        sensorMonitoringClient.disableMonitoring(sensorId);
    }

    @DeleteMapping("{sensorId}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@PathVariable("sensorId") TSID sensorId) {
        Sensor sensor = repository.findById(new SensorId(sensorId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SENSOR_NOT_FOUND));
        if (Boolean.FALSE.equals(sensor.getEnabled())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, SENSOR_ALREADY_DISABLED);
        sensor.setEnabled(false);
        repository.saveAndFlush(sensor);
        sensorMonitoringClient.disableMonitoring(sensorId);
    }
}
