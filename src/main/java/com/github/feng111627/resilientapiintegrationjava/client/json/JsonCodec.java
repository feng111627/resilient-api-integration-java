package com.github.feng111627.resilientapiintegrationjava.client.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 编解码包装层。把 Jackson 异常限制在客户端内部，
 * 上层只看到稳定的 IntegrationException，不直接依赖序列化库异常类型。
 */
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec() {
        this(new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 序列化为 JSON 字节；失败抛出 IllegalStateException（属于编程错误，不发往上游）。 */
    public byte[] writeBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求体序列化失败", e);
        }
    }

    /**
     * 反序列化；失败返回 null，由调用方转换为 UPSTREAM_PARSE_ERROR。
     */
    public <T> T readBytes(byte[] bytes, Class<T> type) {
        try {
            return mapper.readValue(bytes, type);
        } catch (Exception e) {
            return null;
        }
    }

    /** 计算规范化 JSON（字段排序、无多余空白），用于稳定指纹。 */
    public String stableJson(Object value) {
        try {
            ObjectMapper ordered = mapper.copy()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            Object normalized = ordered.convertValue(value, Object.class);
            return ordered.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求指纹计算失败", e);
        }
    }
}
