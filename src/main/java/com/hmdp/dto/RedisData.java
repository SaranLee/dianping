package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class RedisData<T> {
    T data;
    LocalDateTime expireTime;
}
