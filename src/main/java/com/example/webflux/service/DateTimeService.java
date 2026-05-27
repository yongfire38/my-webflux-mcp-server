package com.example.webflux.service;

import reactor.core.publisher.Mono;

public interface DateTimeService {

    Mono<String> getCurrentDateTimeWithZone(String zoneId);
}
