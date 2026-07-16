-- SpiceDB(细粒度判权,docs/09)专用库:与 recsys 业务库同容器不同 database,数据面隔离。
-- 仅在 postgres 卷首次初始化时执行;存量卷需手动: docker exec recsys-postgres createdb -U recsys spicedb
CREATE DATABASE spicedb;
GRANT ALL PRIVILEGES ON DATABASE spicedb TO recsys;
