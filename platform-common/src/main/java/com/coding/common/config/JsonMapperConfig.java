package com.coding.common.config;


import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.jackson.SecurityJacksonModules;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.*;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static tools.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static tools.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;
import static tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS;

@Configuration
public class JsonMapperConfig {

    private final String dateTimePattern = "yyyy-MM-dd HH:mm:ss";

    private final String timePattern = "HH:mm:ss";

    @Bean
    @Primary
    public JsonMapper jsonMapper() {

        SimpleModule javaTimeModule = new SimpleModule();


        javaTimeModule.addSerializer(LocalDateTime.class,
                new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(dateTimePattern)));
        javaTimeModule.addDeserializer(LocalDateTime.class,
                new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(dateTimePattern)));

        javaTimeModule.addSerializer(LocalDate.class,
                new LocalDateSerializer(DateTimeFormatter.ISO_LOCAL_DATE));
        javaTimeModule.addDeserializer(LocalDate.class,
                new LocalDateDeserializer(DateTimeFormatter.ISO_LOCAL_DATE));

        javaTimeModule.addSerializer(LocalTime.class,
                new LocalTimeSerializer(DateTimeFormatter.ofPattern(timePattern)));
        javaTimeModule.addDeserializer(LocalTime.class,
                new LocalTimeDeserializer(DateTimeFormatter.ofPattern(timePattern)));

        javaTimeModule.setSerializerModifier(new ValueSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription.Supplier beanDesc, List<BeanPropertyWriter> beanProperties) {

                for (BeanPropertyWriter beanProperty : beanProperties) {
                    if (isArrayType(beanProperty)) {

                        beanProperty.assignNullSerializer(new ValueSerializer<>() {
                            @Override
                            public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
                                gen.writeStartArray();
                                gen.writeEndArray();
                            }
                        });
                    } else if (isNumber(beanProperty)) {

                        beanProperty.assignNullSerializer(new ValueSerializer<>() {
                            @Override
                            public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
                                gen.writeString("");
                            }
                        });

                    } else if (isBoolean(beanProperty)) {
                        beanProperty.assignNullSerializer(new ValueSerializer<>() {
                            @Override
                            public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
                                gen.writeBoolean(false);
                            }
                        });
                    } else if (isStr(beanProperty) || isDate(beanProperty)) {
                        beanProperty.assignNullSerializer(new ValueSerializer<>() {
                            @Override
                            public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
                                gen.writeString("");
                            }
                        });
                    } else {
                        beanProperty.assignNullSerializer(new ValueSerializer<>() {
                            @Override
                            public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
                                gen.writeStartObject();
                                gen.writeEndObject();
                            }
                        });
                    }
                }

                return super.changeProperties(config, beanDesc, beanProperties);
            }
        });

//        // 创建允许 UnmodifiableMap 的验证器
//        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
//                // 允许 java.util.Collections 及其内部类
//                .allowIfBaseType("java.util.Collections")
//                .allowIfBaseType("java.util.Collections$UnmodifiableMap")
//                .allowIfBaseType("java.util.Map")
//                .allowIfSubType("java.util.HashMap")
//                .allowIfSubType("java.util.LinkedHashMap")
//                .allowIfSubType("java.util.TreeMap")
//                .allowIfSubType("java.util.Collections$UnmodifiableMap")
//                .allowIfSubType("com.coding.data.models.")
//                .allowIfSubType("org.springframework.security.")
//                .allowIfSubTypeIsArray()
//                .build();



        return JsonMapper.builder()
//                .activateDefaultTyping(ptv, DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY)
                .defaultDateFormat(new SimpleDateFormat(dateTimePattern))
                .defaultTimeZone(TimeZone.getTimeZone("GMT+8"))
                .addModule(javaTimeModule)
                .configure(WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(FAIL_ON_EMPTY_BEANS, false)
                .configure(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build();
    }


    /**
     * 判断数组类型
     */
    boolean isArrayType(BeanPropertyWriter writer) {
        Class<?> clazz = writer.getType().getRawClass();
        return clazz.isArray() || clazz.equals(List.class) || clazz.equals(Set.class);

    }

    /**
     * 判断日期类型
     */
    boolean isDate(BeanPropertyWriter writer) {
        Class<?> clazz = writer.getType().getRawClass();
        return clazz.equals(Date.class) || clazz.equals(LocalDateTime.class) || clazz.equals(LocalDate.class) || clazz.equals(LocalTime.class);
    }

    /**
     * 判断bool
     */
    boolean isBoolean(BeanPropertyWriter writer) {
        Class<?> clazz = writer.getType().getRawClass();
        return clazz.equals(Boolean.class) || clazz.equals(boolean.class);
    }
    /**
     * 判断数字类型
     */
    boolean isNumber(BeanPropertyWriter writer) {
        Class<?> clazz = writer.getType().getRawClass();
        return Number.class.isAssignableFrom(clazz)
                || clazz.equals(Short.class) || clazz.equals(short.class)
                || clazz.equals(Integer.class) || clazz.equals(int.class)
                || clazz.equals(Long.class) || clazz.equals(long.class)
                || clazz.equals(Double.class) || clazz.equals(double.class)
                || clazz.equals(Float.class) || clazz.equals(float.class)
                || clazz.equals(BigDecimal.class);
    }

    /**
     * 判断字符类型
     */
    boolean isStr(BeanPropertyWriter writer) {
        Class<?> clazz = writer.getType().getRawClass();
        return clazz.equals(String.class) || clazz.equals(Character.class)
                || clazz.equals(StringBuilder.class) || clazz.equals(StringBuffer.class);
    }

}
