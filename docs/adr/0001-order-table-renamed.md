# 0001 订单表叫 `orders`，不叫 `order`

需求附录写的是 `order`，但 `ORDER` 是 MySQL 保留字，每条手写 SQL 都得加反引号，JPA 也得 `@Table(name = "\`order\`")`，忘了就 1064 语法错误。改叫 `orders`，代码、SQL、文档全部统一，代价只是与需求文档差一个字母，答辩时说明一句即可。`user` 不是保留字，保持原名不动。

## Status

已采纳。数据字典、ER 图、`database.sql` 一律用 `orders`；只在文档里写"需求原文为 `order`"。

## Consequences

与需求文档的表名不一致，需要在数据字典里注明来源；索引命名随之改为 `idx_orders_passenger`。