/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sagerag.rag.config;

import com.sagerag.rag.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐缺失的意图节点。
 * <p>
 * 意图树是检索路由的入口：没有叶子节点时意图分类器拿不到候选，问答会退化成全局向量检索。
 * initFromFactory 按 intentCode 去重，只补缺失节点，不覆盖后台已调整的配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentTreeInitializer implements ApplicationRunner {

    private final IntentTreeService intentTreeService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = intentTreeService.initFromFactory();
            if (created > 0) {
                log.info("已补齐 {} 个缺失的意图节点", created);
            }
        } catch (Exception e) {
            log.warn("意图树初始化失败，请确认知识库种子数据已执行: {}", e.getMessage());
        }
    }
}
