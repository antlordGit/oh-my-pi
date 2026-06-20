-- ===========================================================================
-- 模型配置数据初始化（根据 providers YAML 转换）
-- 每个可用模型一条记录；config_json 存完整 provider 配置
-- 默认激活第一条 DeepSeek V4 Flash
-- ===========================================================================

-- 清空旧的模型配置（可选，按需执行）
-- DELETE FROM model_config;

INSERT INTO model_config (config_name, provider, model_id, base_url, api, api_key, config_json, active, sort_order) VALUES

-- 1. DeepSeek V4 Flash（官方，discovery + modelOverrides，默认激活）
('DeepSeek V4 Flash', 'deepseek', 'deepseek-v4-flash',
 'https://api.deepseek.com/v1', 'openai-completions', 'sk-b7fb05f943d545f5920cb5ac6306ebac',
 '{"provider":"deepseek","baseUrl":"https://api.deepseek.com/v1","apiKey":"sk-b7fb05f943d545f5920cb5ac6306ebac","api":"openai-completions","discovery":{"type":"openai-models-list"},"modelOverrides":{"deepseek-v4-flash":{"contextWindow":1000000,"reasoning":true,"thinking":{"mode":"effort","efforts":["minimal","low","medium","high","xhigh"],"effortMap":{"minimal":"high","low":"high","medium":"high","high":"high","xhigh":"max"}},"compat":{"supportsReasoningEffort":true,"reasoningContentField":"reasoning_content","requiresReasoningContentForToolCalls":true,"requiresAssistantContentForToolCalls":true,"extraBody":{"thinking":{"type":"enabled"}}}},"deepseek-v4-pro":{"contextWindow":1000000,"reasoning":true,"thinking":{"mode":"effort","efforts":["minimal","low","medium","high","xhigh"],"effortMap":{"minimal":"high","low":"high","medium":"high","high":"high","xhigh":"max"}},"compat":{"supportsReasoningEffort":true,"reasoningContentField":"reasoning_content","requiresReasoningContentForToolCalls":true,"requiresAssistantContentForToolCalls":true,"extraBody":{"thinking":{"type":"enabled"}}}}}}',
 TRUE, 0),

-- 2. DeepSeek V4 Pro（官方）
('DeepSeek V4 Pro', 'deepseek', 'deepseek-v4-pro',
 'https://api.deepseek.com/v1', 'openai-completions', 'sk-b7fb05f943d545f5920cb5ac6306ebac',
 '{"provider":"deepseek","baseUrl":"https://api.deepseek.com/v1","apiKey":"sk-b7fb05f943d545f5920cb5ac6306ebac","api":"openai-completions","discovery":{"type":"openai-models-list"},"modelOverrides":{"deepseek-v4-flash":{"contextWindow":1000000,"reasoning":true,"thinking":{"mode":"effort","efforts":["minimal","low","medium","high","xhigh"],"effortMap":{"minimal":"high","low":"high","medium":"high","high":"high","xhigh":"max"}},"compat":{"supportsReasoningEffort":true,"reasoningContentField":"reasoning_content","requiresReasoningContentForToolCalls":true,"requiresAssistantContentForToolCalls":true,"extraBody":{"thinking":{"type":"enabled"}}}},"deepseek-v4-pro":{"contextWindow":1000000,"reasoning":true,"thinking":{"mode":"effort","efforts":["minimal","low","medium","high","xhigh"],"effortMap":{"minimal":"high","low":"high","medium":"high","high":"high","xhigh":"max"}},"compat":{"supportsReasoningEffort":true,"reasoningContentField":"reasoning_content","requiresReasoningContentForToolCalls":true,"requiresAssistantContentForToolCalls":true,"extraBody":{"thinking":{"type":"enabled"}}}}}}',
 FALSE, 1),

-- 3. Deepinet V4 Flash（第三方中转，models 列表）
('Deepinet V4 Flash', 'deepinet', 'deepseek-v4-flash',
 'https://token.deepinet.com/v1', 'openai-completions', 'sk-4oShTHCt6ZoYsmUSF942A2Dd8b6d4a90A60e82197b3077Fe',
 '{"provider":"deepinet","baseUrl":"https://token.deepinet.com/v1","apiKey":"sk-4oShTHCt6ZoYsmUSF942A2Dd8b6d4a90A60e82197b3077Fe","api":"openai-completions","models":[{"id":"deepseek-v4-flash","name":"DeepSeek V4 Flash (Deepinet)","contextWindow":1000000,"maxTokens":8192,"reasoning":true,"thinking":{"mode":"effort","efforts":["minimal","low","medium","high","xhigh"],"effortMap":{"minimal":"high","low":"high","medium":"high","high":"high","xhigh":"max"}},"compat":{"supportsReasoningEffort":true,"reasoningContentField":"reasoning_content","requiresReasoningContentForToolCalls":true,"requiresAssistantContentForToolCalls":true,"extraBody":{"thinking":{"type":"enabled"}}}}]}',
 FALSE, 2),

-- 4. StepFun 3.7 Flash
('Step 3.7 Flash', 'stepfun', 'step-3.7-flash',
 'https://api.stepfun.com/step_plan/v1', 'openai-completions', '1AqKnVXBJYHctryVvu1sGt8PLrWjoBuZLPwgib95i5Kjrv9IKXGCL7Rj8xUqcVUWb',
 '{"provider":"stepfun","baseUrl":"https://api.stepfun.com/step_plan/v1","apiKey":"1AqKnVXBJYHctryVvu1sGt8PLrWjoBuZLPwgib95i5Kjrv9IKXGCL7Rj8xUqcVUWb","api":"openai-completions","models":[{"id":"step-3.7-flash","name":"Step 3.7 Flash","contextWindow":256000,"maxTokens":8192}]}',
 FALSE, 3),

-- 5. MiniMax M3（anthropic-messages）
('MiniMax M3', 'minimax', 'MiniMax-M3',
 'https://api.minimaxi.com/anthropic', 'anthropic-messages', 'sk-cp-dKn-OYJy6rst6IZAUaVKkEZ7-nQ6ZRaR-YwdKb8Fu7TYeCleVC7taFrEGEHzvpvF6antGMXF9SzVhOYxreyaYEuNuYJAUQIIKFHmqOz-pdp1coSzP1X_L3A',
 '{"provider":"minimax","baseUrl":"https://api.minimaxi.com/anthropic","apiKey":"sk-cp-dKn-OYJy6rst6IZAUaVKkEZ7-nQ6ZRaR-YwdKb8Fu7TYeCleVC7taFrEGEHzvpvF6antGMXF9SzVhOYxreyaYEuNuYJAUQIIKFHmqOz-pdp1coSzP1X_L3A","api":"anthropic-messages","models":[{"id":"MiniMax-M3","name":"MiniMax M3","contextWindow":1000000,"maxTokens":8192}]}',
 FALSE, 4),

-- 6. 讯飞 Astron Code（anthropic-messages）
('讯飞 Astron Code', 'xunfei', 'astron-code-latest',
 'https://maas-coding-api.cn-huabei-1.xf-yun.com/anthropic', 'anthropic-messages', '71f8e53a44d28a6bebd968216e0796c8:NzRjMzBmMDZmZjdmZGJkZDU2YmI4M2Jk',
 '{"provider":"xunfei","baseUrl":"https://maas-coding-api.cn-huabei-1.xf-yun.com/anthropic","apiKey":"71f8e53a44d28a6bebd968216e0796c8:NzRjMzBmMDZmZjdmZGJkZDU2YmI4M2Jk","api":"anthropic-messages","models":[{"id":"astron-code-latest","name":"Astron Code (讯飞)","contextWindow":200000,"maxTokens":8192}]}',
 FALSE, 5),

-- 7. 豆包 GLM Latest（anthropic-messages）
('豆包 GLM Latest', 'doubao', 'glm-latest',
 'https://ark.cn-beijing.volces.com/api/coding', 'anthropic-messages', '10a66033-7b26-4db2-8acf-3bfafd5e3ae1',
 '{"provider":"doubao","baseUrl":"https://ark.cn-beijing.volces.com/api/coding","apiKey":"10a66033-7b26-4db2-8acf-3bfafd5e3ae1","api":"anthropic-messages","models":[{"id":"glm-latest","name":"GLM Latest","contextWindow":1000000,"maxTokens":8192}]}',
 FALSE, 6),

-- 8. DeepSeek Self（官方 anthropic 端点）
('DeepSeek Self (Anthropic)', 'deepseek-self', 'deepseek-v4-flash',
 'https://api.deepseek.com/anthropic', 'anthropic-messages', 'sk-334a6c95c7c44b0ea61d1c134ace1dcc',
 '{"provider":"deepseek-self","baseUrl":"https://api.deepseek.com/anthropic","apiKey":"sk-334a6c95c7c44b0ea61d1c134ace1dcc","api":"anthropic-messages","models":[{"id":"deepseek-v4-flash","name":"deepseek-v4-flash","contextWindow":1000000,"maxTokens":8192}]}',
 FALSE, 7);
