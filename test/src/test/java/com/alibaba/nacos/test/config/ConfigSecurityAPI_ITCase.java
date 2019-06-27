/*
 * Dian.so Inc.
 * Copyright (c) 2016-2019 All Rights Reserved.
 */
package com.alibaba.nacos.test.config;

import com.alibaba.nacos.config.server.Config;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URL;

/**
 * TODO
 *
 * @author baizhang
 * @version: v 0.1 ConfigSecurityController.java, 2019-06-27 15:52 Exp $
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = Config.class, properties = {"server.servlet.context-path=/nacos",
    "server.port=7001"},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ConfigSecurityAPI_ITCase {
    @LocalServerPort
    private int port;

    private URL base;

    @Autowired
    private TestRestTemplate restTemplate;

    @Before
    public void setUp() throws Exception {
        String url = String.format("http://localhost:%d/", port);
        this.base = new URL(url);
        //prepareData();
    }

    @Test
    public void test() throws Exception {
        String context= "123abc";

        ResponseEntity<String> responseEncrypt= request("/nacos/v1/cs/encrypt",
            context,
            String.class,
            HttpMethod.POST);


        ResponseEntity<String> responseDecrypt = request("/nacos/v1/cs/decrypt",
            responseEncrypt.getBody(),
            String.class,
            HttpMethod.POST);


        Assert.assertEquals(context, responseDecrypt.getBody());
    }


    private <T> ResponseEntity<T> request(String path, String params,
                                          Class<T> clazz, HttpMethod httpMethod) {

        HttpHeaders headers = new HttpHeaders();
        HttpEntity<?> entity = new HttpEntity<T>(headers);

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(this.base.toString() + path)
            .query(params);

        return this.restTemplate.exchange(builder.toUriString(), httpMethod, entity, clazz);
    }
}
