/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.config.server.controller;

import com.alibaba.nacos.config.server.constant.Constants;
import com.alibaba.nacos.config.server.factory.YamlPropertySourceFactory;
import com.alibaba.nacos.config.server.model.CacheItem;
import com.alibaba.nacos.config.server.model.ConfigInfoBase;
import com.alibaba.nacos.config.server.service.ConfigService;
import com.alibaba.nacos.config.server.service.DiskUtil;
import com.alibaba.nacos.config.server.service.LongPollingService;
import com.alibaba.nacos.config.server.service.PersistService;
import com.alibaba.nacos.config.server.service.trace.ConfigTraceService;
import com.alibaba.nacos.config.server.model.NacosPropertySource;
import com.alibaba.nacos.config.server.utils.GroupKey2;
import com.alibaba.nacos.config.server.utils.LogUtil;
import com.alibaba.nacos.config.server.utils.MD5Util;
import com.alibaba.nacos.config.server.utils.PropertyUtil;
import com.alibaba.nacos.config.server.utils.Protocol;
import com.alibaba.nacos.config.server.utils.RequestUtil;
import com.alibaba.nacos.config.server.utils.TimeUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.DefaultPropertySourceFactory;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.alibaba.nacos.config.server.utils.LogUtil.pullLog;
import static com.alibaba.nacos.core.utils.SystemUtils.STANDALONE_MODE;

/**
 * ConfigServlet inner for aop
 *
 * @author Nacos
 */
@Service
public class ConfigServletInner {

    @Autowired
    private LongPollingService longPollingService;

    @Autowired
    private PersistService persistService;

    private static final int TRY_GET_LOCK_TIMES = 9;

    private static final int START_LONGPOLLING_VERSION_NUM = 204;

    private static final String ENCODING_UTF8="UTF-8";

    /**
     * 轮询接口
     */
    public String doPollingConfig(HttpServletRequest request, HttpServletResponse response,
                                  Map<String, String> clientMd5Map, int probeRequestSize)
        throws IOException, ServletException {

        // 长轮询
        if (LongPollingService.isSupportLongPolling(request)) {
            longPollingService.addLongPollingClient(request, response, clientMd5Map, probeRequestSize);
            return HttpServletResponse.SC_OK + "";
        }

        // else 兼容短轮询逻辑
        List<String> changedGroups = MD5Util.compareMd5(request, response, clientMd5Map);

        // 兼容短轮询result
        String oldResult = MD5Util.compareMd5OldResult(changedGroups);
        String newResult = MD5Util.compareMd5ResultString(changedGroups);

        String version = request.getHeader(Constants.CLIENT_VERSION_HEADER);
        if (version == null) {
            version = "2.0.0";
        }
        int versionNum = Protocol.getVersionNumber(version);

        /**
         * 2.0.4版本以前, 返回值放入header中
         */
        if (versionNum < START_LONGPOLLING_VERSION_NUM) {
            response.addHeader(Constants.PROBE_MODIFY_RESPONSE, oldResult);
            response.addHeader(Constants.PROBE_MODIFY_RESPONSE_NEW, newResult);
        } else {
            request.setAttribute("content", newResult);
        }

        // 禁用缓存
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
        response.setHeader("Cache-Control", "no-cache,no-store");
        response.setStatus(HttpServletResponse.SC_OK);
        return HttpServletResponse.SC_OK + "";
    }

    /**
     * 同步配置获取接口
     */
    public String doGetConfig(HttpServletRequest request, HttpServletResponse response, String dataId, String group,
                              String tenant, String tag, String clientIp) throws IOException, ServletException {
        final String groupKey = GroupKey2.getKey(dataId, group, tenant);
        String autoTag = request.getHeader("Vipserver-Tag");
        String requestIpApp = RequestUtil.getAppName(request);
        int lockResult = tryConfigReadLock(request, response, groupKey);

        final String requestIp = RequestUtil.getRemoteIp(request);
        boolean isBeta = false;
        if (lockResult > 0) {
            FileInputStream fis = null;
            try {
                String md5 = Constants.NULL;
                long lastModified = 0L;
                CacheItem cacheItem = ConfigService.getContentCache(groupKey);
                if (cacheItem != null) {
                    if (cacheItem.isBeta()) {
                        if (cacheItem.getIps4Beta().contains(clientIp)) {
                            isBeta = true;
                        }
                    }
                }
                File file = null;
                ConfigInfoBase configInfoBase = null;
                PrintWriter out = null;
                if (isBeta) {
                    md5 = cacheItem.getMd54Beta();
                    lastModified = cacheItem.getLastModifiedTs4Beta();
                    if (STANDALONE_MODE && !PropertyUtil.isStandaloneUseMysql()) {
                        configInfoBase = persistService.findConfigInfo4Beta(dataId, group, tenant);
                    } else {
                        file = DiskUtil.targetBetaFile(dataId, group, tenant);
                    }
                    response.setHeader("isBeta", "true");
                } else {
                    if (StringUtils.isBlank(tag)) {
                        if (isUseTag(cacheItem, autoTag)) {
                            if (cacheItem != null) {
                                if (cacheItem.tagMd5 != null) {
                                    md5 = cacheItem.tagMd5.get(autoTag);
                                }
                                if (cacheItem.tagLastModifiedTs != null) {
                                    lastModified = cacheItem.tagLastModifiedTs.get(autoTag);
                                }
                            }
                            if (STANDALONE_MODE && !PropertyUtil.isStandaloneUseMysql()) {
                                configInfoBase = persistService.findConfigInfo4Tag(dataId, group, tenant, autoTag);
                            } else {
                                file = DiskUtil.targetTagFile(dataId, group, tenant, autoTag);
                            }

                            response.setHeader("Vipserver-Tag",
                                URLEncoder.encode(autoTag, StandardCharsets.UTF_8.displayName()));
                        } else {
                            md5 = cacheItem.getMd5();
                            lastModified = cacheItem.getLastModifiedTs();
                            if (STANDALONE_MODE && !PropertyUtil.isStandaloneUseMysql()) {
                                configInfoBase = persistService.findConfigInfo(dataId, group, tenant);
                            } else {
                                file = DiskUtil.targetFile(dataId, group, tenant);
                            }
                            if (configInfoBase == null && fileNotExist(file)) {
                                // FIXME CacheItem
                                // 不存在了无法简单的计算推送delayed，这里简单的记做-1
                                ConfigTraceService.logPullEvent(dataId, group, tenant, requestIpApp, -1,
                                    ConfigTraceService.PULL_EVENT_NOTFOUND, -1, requestIp);

                                // pullLog.info("[client-get] clientIp={}, {},
                                // no data",
                                // new Object[]{clientIp, groupKey});

                                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                                response.getWriter().println("config data not exist");
                                return HttpServletResponse.SC_NOT_FOUND + "";
                            }
                        }
                    } else {
                        if (cacheItem != null) {
                            if (cacheItem.tagMd5 != null) {
                                md5 = cacheItem.tagMd5.get(tag);
                            }
                            if (cacheItem.tagLastModifiedTs != null) {
                                Long lm = cacheItem.tagLastModifiedTs.get(tag);
                                if (lm != null) {
                                    lastModified = lm;
                                }
                            }
                        }
                        if (STANDALONE_MODE && !PropertyUtil.isStandaloneUseMysql()) {
                            configInfoBase = persistService.findConfigInfo4Tag(dataId, group, tenant, tag);
                        } else {
                            file = DiskUtil.targetTagFile(dataId, group, tenant, tag);
                        }
                        if (configInfoBase == null && fileNotExist(file)) {
                            // FIXME CacheItem
                            // 不存在了无法简单的计算推送delayed，这里简单的记做-1
                            ConfigTraceService.logPullEvent(dataId, group, tenant, requestIpApp, -1,
                                ConfigTraceService.PULL_EVENT_NOTFOUND,
                                -1, requestIp);

                            // pullLog.info("[client-get] clientIp={}, {},
                            // no data",
                            // new Object[]{clientIp, groupKey});

                            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                            response.getWriter().println("config data not exist");
                            return HttpServletResponse.SC_NOT_FOUND + "";
                        }
                    }
                }

                response.setHeader(Constants.CONTENT_MD5, md5);
                /**
                 *  禁用缓存
                 */
                response.setHeader("Pragma", "no-cache");
                response.setDateHeader("Expires", 0);
                response.setHeader("Cache-Control", "no-cache,no-store");
                if (STANDALONE_MODE && !PropertyUtil.isStandaloneUseMysql()) {
                    response.setDateHeader("Last-Modified", lastModified);
                } else {
                    fis = new FileInputStream(file);
                    response.setDateHeader("Last-Modified", file.lastModified());
                }
                if (STANDALONE_MODE && !PropertyUtil.isStandaloneUseMysql()) {
                    out = response.getWriter();
                    // 配置内容不包含需要解密的内容，直接返回
                    if(configInfoBase.getContent().contains(Constants.PREFIX_CIPHER)){
                        PropertySource<?> propertySource = null;
                        // yaml yml配置文件
                        if (configInfoBase.getDataId().indexOf(YAML_SUFFIX) > 0 ||
                            configInfoBase.getDataId().indexOf(YML_SUFFIX) > 0) {
                            propertySource = YAML_SOURCE_FACTORY.createPropertySource(configInfoBase.getDataId(),
                                new EncodedResource(new ByteArrayResource(configInfoBase.getContent().getBytes()), Constants.ENCODE));
                            // 数据转换
                            Map<String, Object> map = convertToMap(propertySource);
                            out.print(new Yaml().dumpAsMap(map));
                        // properties文件
                        } else if (configInfoBase.getDataId().indexOf(PROPERTIES_SUFFIX) > 0) {
                            propertySource = PROPERTIES_SOURCE_FACTORY.createPropertySource(configInfoBase.getDataId(),
                                new EncodedResource(new ByteArrayResource(configInfoBase.getContent().getBytes()), Constants.ENCODE));
                            // 数据转换
                            Map<String, Object> map = convertToProperties(propertySource);
                            out.print(getPropertiesString(map));
                        }
                    }else{
                        out.print(configInfoBase.getContent());
                    }

                    out.flush();
                    out.close();
                } else {
                    fis.getChannel().transferTo(0L, fis.getChannel().size(),
                        Channels.newChannel(response.getOutputStream()));
                }

                LogUtil.pullCheckLog.warn("{}|{}|{}|{}", groupKey, requestIp, md5, TimeUtils.getCurrentTimeStr());

                final long delayed = System.currentTimeMillis() - lastModified;

                // TODO distinguish pull-get && push-get
                // 否则无法直接把delayed作为推送延时的依据，因为主动get请求的delayed值都很大
                ConfigTraceService.logPullEvent(dataId, group, tenant, requestIpApp, lastModified,
                    ConfigTraceService.PULL_EVENT_OK, delayed,
                    requestIp);

            } finally {
                releaseConfigReadLock(groupKey);
                if (null != fis) {
                    fis.close();
                }
            }
        } else if (lockResult == 0) {

            // FIXME CacheItem 不存在了无法简单的计算推送delayed，这里简单的记做-1
            ConfigTraceService.logPullEvent(dataId, group, tenant, requestIpApp, -1,
                ConfigTraceService.PULL_EVENT_NOTFOUND, -1, requestIp);

            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().println("config data not exist");
            return HttpServletResponse.SC_NOT_FOUND + "";

        } else {

            pullLog.info("[client-get] clientIp={}, {}, get data during dump", clientIp, groupKey);

            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.getWriter().println("requested file is being modified, please try later.");
            return HttpServletResponse.SC_CONFLICT + "";

        }

        return HttpServletResponse.SC_OK + "";
    }

    /** =========================================================================================== **/

    private static final PropertySourceFactory PROPERTIES_SOURCE_FACTORY = new DefaultPropertySourceFactory();
    private static final PropertySourceFactory YAML_SOURCE_FACTORY = new YamlPropertySourceFactory();
    @Autowired
    private TextEncryptor encryptor;

    private String getPropertiesString(Map<String, Object> properties) {
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (output.length() > 0) {
                output.append("\n");
            }
            String line = entry.getKey() + ": " + entry.getValue();
            output.append(line);
        }
        return output.toString();
    }

    private Map<String, Object> convertToMap(PropertySource<?> propertySource) {
        // First use the current convertToProperties to get a flat Map from the environment
        Map<String, Object> properties = convertToProperties(propertySource);
        // The root map which holds all the first level properties
        Map<String, Object> rootMap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            PropertyNavigator nav = new PropertyNavigator(key);
            nav.setMapValue(rootMap, value);
        }
        return rootMap;
    }


    private NacosPropertySource convert(PropertySource<?> propertySource){
        return new NacosPropertySource(propertySource.getName(), (Map<?, ?>) propertySource.getSource());
    }


    private Map<String, Object> convertToProperties(PropertySource<?> propertySource) {
        // Map of unique keys containing full map of properties for each unique
        // key
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        Map<String, Object> combinedMap = new TreeMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> mapSource = (Map<String, Object>) convert(propertySource).getSource();
        for (Map.Entry<String, Object> entry : mapSource.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if(value.toString().startsWith(Constants.PREFIX_CIPHER)){
                String cipher = value.toString().substring(Constants.PREFIX_CIPHER.length());
                try {
                    value = encryptor.decrypt(cipher);
                } catch (Exception e) {
                    value = Constants.NOT_APPLICABLE;
                    String message = "Cannot decrypt key: " + key + " (" + e.getClass()
                        + ": " + e.getMessage() + ")";
                    LogUtil.pullCheckLog.warn(message, e);
                }
            }
            if (!key.contains(Constants.RANGE_INCLUDE_LEFT)) {
                // Not an array, add unique key to the map
                combinedMap.put(key, value);
            } else {
                // An existing array might have already been added to the property map
                // of an unequal size to the current array. Replace the array key in
                // the current map.
                key = key.substring(0, key.indexOf(Constants.RANGE_INCLUDE_LEFT));
                Map<String, Object> filtered = new TreeMap<>();
                for (String index : mapSource.keySet()) {
                    if (index.startsWith(key + Constants.RANGE_INCLUDE_LEFT)) {
                        filtered.put(index, mapSource.get(index));
                    }
                }
                map.put(key, filtered);
            }
        }
        // Combine all unique keys for array values into the combined map
        for (Map.Entry<String, Map<String, Object>> entry : map.entrySet()) {
            combinedMap.putAll(entry.getValue());
        }
        postProcessProperties(combinedMap);
        return combinedMap;
    }

    private void postProcessProperties(Map<String, Object> propertiesMap) {
        for (Iterator<String> iter = propertiesMap.keySet().iterator(); iter.hasNext(); ) {
            String key = iter.next();
            if ("spring.profiles".equals(key)) {
                iter.remove();
            }
        }
    }




    /**
     * Class {@code PropertyNavigator} is used to navigate through the property key and create necessary Maps and Lists
     * making up the nested structure to finally set the property value at the leaf node.
     * <p>
     * The following rules in yml/json are implemented:
     * <pre>
     * 1. an array element can be:
     *    - a value (leaf)
     *    - a map
     *    - a nested array
     * 2. a map value can be:
     *    - a value (leaf)
     *    - a nested map
     *    - an array
     * </pre>
     */
    private static class PropertyNavigator {

        private enum NodeType {
            /**
             * leaf
             */
            LEAF,
            /**
             * map
             */
            MAP,
            /**
             * arrays
             */
            ARRAY
        }

        private final String propertyKey;
        private int currentPos;
        private NodeType valueType;

        private PropertyNavigator(String propertyKey) {
            this.propertyKey = propertyKey;
            currentPos = -1;
            valueType = NodeType.MAP;
        }

        private void setMapValue(Map<String, Object> map, Object value) {
            String key = getKey();
            if (NodeType.MAP.equals(valueType)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) map.get(key);
                if (nestedMap == null) {
                    nestedMap = new LinkedHashMap<>();
                    map.put(key, nestedMap);
                }
                setMapValue(nestedMap, value);
            } else if (NodeType.ARRAY.equals(valueType)) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) map.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    map.put(key, list);
                }
                setListValue(list, value);
            } else {
                map.put(key, value);
            }
        }

        private void setListValue(List<Object> list, Object value) {
            int index = getIndex();
            // Fill missing elements if needed
            while (list.size() <= index) {
                list.add(null);
            }
            if (NodeType.MAP.equals(valueType)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) list.get(index);
                if (map == null) {
                    map = new LinkedHashMap<>();
                    list.set(index, map);
                }
                setMapValue(map, value);
            } else if (NodeType.ARRAY.equals(valueType)) {
                @SuppressWarnings("unchecked")
                List<Object> nestedList = (List<Object>) list.get(index);
                if (nestedList == null) {
                    nestedList = new ArrayList<>();
                    list.set(index, nestedList);
                }
                setListValue(nestedList, value);
            } else {
                list.set(index, value);
            }
        }

        private int getIndex() {
            // Consider [
            int start = currentPos + 1;

            for (int i = start; i < propertyKey.length(); i++) {
                char c = propertyKey.charAt(i);
                if (c == ']') {
                    currentPos = i;
                    break;
                } else if (!Character.isDigit(c)) {
                    throw new IllegalArgumentException("Invalid key: " + propertyKey);
                }
            }
            // If no closing ] or if '[]'
            if (currentPos < start || currentPos == start) {
                throw new IllegalArgumentException("Invalid key: " + propertyKey);
            } else {
                int index = Integer.parseInt(propertyKey.substring(start, currentPos));
                // Skip the closing ]
                currentPos++;
                if (currentPos == propertyKey.length()) {
                    valueType = NodeType.LEAF;
                } else {
                    switch (propertyKey.charAt(currentPos)) {
                        case '.':
                            valueType = NodeType.MAP;
                            break;
                        case '[':
                            valueType = NodeType.ARRAY;
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid key: " + propertyKey);
                    }
                }
                return index;
            }
        }

        private String getKey() {
            // Consider initial value or previous char '.' or '['
            int start = currentPos + 1;
            for (int i = start; i < propertyKey.length(); i++) {
                char currentChar = propertyKey.charAt(i);
                if (currentChar == '.') {
                    valueType = NodeType.MAP;
                    currentPos = i;
                    break;
                } else if (currentChar == '[') {
                    valueType = NodeType.ARRAY;
                    currentPos = i;
                    break;
                }
            }
            // If there's no delimiter then it's a key of a leaf
            if (currentPos < start) {
                currentPos = propertyKey.length();
                valueType = NodeType.LEAF;
                // Else if we encounter '..' or '.[' or start of the property is . or [ then it's invalid
            } else if (currentPos == start) {
                throw new IllegalArgumentException("Invalid key: " + propertyKey);
            }
            return propertyKey.substring(start, currentPos);
        }
    }

    private static final String YAML_SUFFIX = ".yaml";
    private static final String YML_SUFFIX = ".yml";
    private static final String PROPERTIES_SUFFIX = ".properties";

    /** =========================================================================================== **/


    private static void releaseConfigReadLock(String groupKey) {
        ConfigService.releaseReadLock(groupKey);
    }

    private static int tryConfigReadLock(HttpServletRequest request, HttpServletResponse response, String groupKey)
        throws IOException, ServletException {
        /**
         *  默认加锁失败
         */
        int lockResult = -1;
        /**
         *  尝试加锁，最多10次
         */
        for (int i = TRY_GET_LOCK_TIMES; i >= 0; --i) {
            lockResult = ConfigService.tryReadLock(groupKey);
            /**
             *  数据不存在
             */
            if (0 == lockResult) {
                break;
            }

            /**
             *  success
             */
            if (lockResult > 0) {
                break;
            }
            /**
             *  retry
             */
            if (i > 0) {
                try {
                    Thread.sleep(1);
                } catch (Exception e) {
                }
            }
        }

        return lockResult;
    }

    private static boolean isUseTag(CacheItem cacheItem, String tag) {
        if (cacheItem != null && cacheItem.tagMd5 != null && cacheItem.tagMd5.size() > 0) {
            return StringUtils.isNotBlank(tag) && cacheItem.tagMd5.containsKey(tag);
        }
        return false;
    }

    private static boolean fileNotExist(File file) {
        return file == null || !file.exists();
    }

}
