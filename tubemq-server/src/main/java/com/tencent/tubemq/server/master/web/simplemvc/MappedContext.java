/*
 * Tencent is pleased to support the open source community by making TubeMQ available.
 *
 * Copyright (C) 2012-2019 Tencent. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.tubemq.server.master.web.simplemvc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


public class MappedContext implements Context {

    private Map map;

    public MappedContext() {
        this.map = new HashMap();
    }

    @Override
    public void put(String key, Object value) {
        map.put(key, value);
    }

    @Override
    public Object get(String key) {
        return map.get(key);
    }

    @Override
    public void remove(String key) {
        map.remove(key);
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public void destory() {
        map = Collections.emptyMap();
    }

    @Override
    public boolean containsKey(String key) {
        return map.containsKey(key);
    }

    @Override
    public Set<String> keySet() {
        return map.keySet();
    }

    public Map getMap() {
        return map;
    }
}
