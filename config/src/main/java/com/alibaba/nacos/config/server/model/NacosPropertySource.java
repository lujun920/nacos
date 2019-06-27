/*
 * Dian.so Inc.
 * Copyright (c) 2016-2019 All Rights Reserved.
 */
package com.alibaba.nacos.config.server.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * TODO
 *
 * @author baizhang
 * @version: v 0.1 NacosPropertySource.java, 2019-06-19 21:40 Exp $
 */
public class NacosPropertySource {
    private String name;

    private Map<?, ?> source;

    @JsonCreator
    public NacosPropertySource(@JsonProperty("name") String name,
                               @JsonProperty("source") Map<?, ?> source) {
        this.name = name;
        this.source = source;
    }

    public String getName() {
        return name;
    }

    public Map<?, ?> getSource() {
        return source;
    }

    @Override
    public String toString() {
        return "NacosPropertySource [name=" + name + "]";
    }

}
