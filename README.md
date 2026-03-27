# Jik-taste

## 项目简介
`hm-dianping` 是一个基于 Spring Boot 的后端服务，主要实现了团购点评/小程序后端常见能力的简化版：店铺查询、店铺类型、用户登录（短信验证码模拟）、博客发布与点赞、关注（目前接口类为空）、秒杀优惠券下单（Redis Lua + Kafka 异步下单）、以及图片上传。

## 技术栈
- Java 8
- Spring Boot 2.3.12.RELEASE
- MyBatis-Plus
- MySQL
- Redis（缓存与登录态）
- Kafka（秒杀下单异步处理）
- Caffeine（本地二级缓存 L1）
- Redisson
- Hutool（JSON、工具类等）

## 环境准备
1. 准备 MySQL 数据库
   - 数据库初始化脚本：`src/main/resources/db/hmdp.sql`
   - application 配置中默认使用库名 `hmdp`
2. 准备 Redis
   - 默认：`localhost:6379`
   - 使用场景：验证码/Token、缓存（店铺二级缓存等）
3. 准备 Kafka
   - 默认：`localhost:9092`
   - 秒杀下单时生产与消费同一 Topic：`voucher-order-topic`

## 配置说明
入口配置文件：`src/main/resources/application.yaml`

你需要重点检查以下字段是否符合你的本机环境：
- `spring.datasource.url/username/password`
- `spring.redis.host/port/database`
- `spring.kafka.bootstrap-servers`

图片上传目录在 `com.jktt.utils.SystemConstants#IMAGE_UPLOAD_DIR` 中配置（默认是一个本地绝对路径）。

## 启动方式
方式一：直接运行启动类 `com.jktt.HmDianPingApplication`

方式二：Maven 启动
```bash
mvn spring-boot:run
```

服务默认端口：`8081`

## 认证与拦截器
- 登录鉴权拦截器：`com.jktt.config.MvcConfig`
- 规则：
  - 客户端需要在请求头携带 `authorization`，拦截器会从 Redis 读取 `login:token:{token}` 对应的用户信息
  - `RefreshTokenInterceptor` 会在每次请求时刷新 token 的过期时间

## 缓存策略（Caffeine + Redis 二级缓存）
店铺详情查询使用了二级缓存：
- L1：Caffeine（本地缓存）
- L2：Redis（逻辑过期存储结构）
- 对击穿：通过 Redis 分布式锁（`lock:shop:{id}`）异步重建缓存

二级缓存实现类：`com.jktt.utils.TwoLevelCacheClient`

## 秒杀优惠券下单（Redis Lua + Kafka）
秒杀下单流程：
1. `VoucherOrderServiceImpl#seckillVoucher(voucherId)` 执行 Lua 脚本 `src/main/resources/seckill.lua`
   - 校验库存是否充足
   - 校验用户是否重复下单（`seckill:order:{voucherId}` 的集合是否包含 userId）
   - 扣减库存与记录下单信息在 Redis 内完成
2. 校验通过后生成订单号（`RedisIdWorker`）并将订单消息发送到 Kafka
   - Topic：`voucher-order-topic`
3. 消费者 `@KafkaListener` 接收消息并在事务中：
   - 扣减数据库库存
   - 保存 `VoucherOrder` 记录

## API 接口
### 店铺
- `GET /shop/{id}`：查询店铺详情（走二级缓存）
- `POST /shop`：新增店铺
- `PUT /shop`：更新店铺（更新后会删除本地与 Redis 缓存）
- `GET /shop/of/type?typeId={typeId}&current={current}`：按类型分页查询
- `GET /shop/of/name?name={name}&current={current}`：按名称关键字分页查询

### 店铺类型
- `GET /shop-type/list`：查询所有店铺类型

### 优惠券
- `POST /voucher`：新增普通券
- `POST /voucher/seckill`：新增秒杀券（包含秒杀库存与时间）
- `GET /voucher/list/{shopId}`：查询指定店铺的优惠券列表

### 秒杀下单
- `POST /voucher-order/seckill/{id}`：秒杀下单（参数为 `voucherId`）

### 博客
- `POST /blog`：发布博客（写入用户信息）
- `PUT /blog/like/{id}`：点赞博客（liked + 1）
- `GET /blog/of/me?current={current}`：查询当前用户的博客列表
- `GET /blog/hot?current={current}`：查询热榜博客（按 liked 倒序）

### 用户登录（验证码模拟）
- `POST /user/code?phone={phone}`：发送短信验证码（默认只在日志里输出验证码）
- `POST /user/login`：登录（入参为 `phone + code`）
- `GET /user/me`：查询当前登录用户信息
- `GET /user/info/{id}`：查询用户详情
- `POST /user/logout`：当前标记为未完成（返回失败）

### 图片上传
- `POST /upload/blog`：上传博客图片（表单字段 `file`）
- `GET /upload/blog/delete?name={filename}`：删除图片

## 备注
- `BlogCommentsController` 和 `FollowController` 当前类里未实现具体接口（路由存在但为空）。
- 项目中存在 Redis 缓存、Lua 脚本、Kafka 异步处理等机制；部署时请确保三个中间件均可用。
