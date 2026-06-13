-- 数据库智能查询 Agent 测试表结构
-- 请在 MySQL 中执行此脚本

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS business_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE business_db;

-- 1. 用户信息表
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    email VARCHAR(100) COMMENT '邮箱地址',
    phone VARCHAR(20) COMMENT '手机号',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status (status),
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';

-- 2. 商品表
CREATE TABLE products (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '商品ID',
    name VARCHAR(200) NOT NULL COMMENT '商品名称',
    category_id BIGINT COMMENT '分类ID',
    price DECIMAL(10,2) NOT NULL COMMENT '商品价格',
    stock INT DEFAULT 0 COMMENT '库存数量',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-下架, 1-上架',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_status (status),
    INDEX idx_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 3. 订单表
CREATE TABLE orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '订单ID',
    order_no VARCHAR(32) NOT NULL COMMENT '订单编号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
    status TINYINT DEFAULT 0 COMMENT '订单状态: 0-待支付, 1-已支付, 2-已发货, 3-已完成, 4-已取消',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    paid_at DATETIME COMMENT '支付时间',
    UNIQUE INDEX idx_order_no (order_no),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 4. 订单明细表
CREATE TABLE order_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '明细ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    quantity INT NOT NULL COMMENT '购买数量',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '商品单价',
    subtotal DECIMAL(10,2) NOT NULL COMMENT '小计金额',
    INDEX idx_order_id (order_id),
    INDEX idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- Agent 会话历史表
CREATE TABLE IF NOT EXISTS agent_conversations (
    id VARCHAR(64) PRIMARY KEY COMMENT '会话ID',
    title VARCHAR(255) NOT NULL COMMENT '会话标题',
    last_message VARCHAR(500) NOT NULL COMMENT '最近消息摘要',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent会话历史表';

-- Agent 会话消息表
CREATE TABLE IF NOT EXISTS agent_messages (
    id VARCHAR(64) PRIMARY KEY COMMENT '消息ID',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    role VARCHAR(20) NOT NULL COMMENT '消息角色',
    content LONGTEXT NOT NULL COMMENT '消息内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_conversation_created_at (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent会话消息表';

-- ========================================
-- 插入测试数据
-- ========================================

-- 用户数据
INSERT INTO users (username, email, phone, status) VALUES
('张三', 'zhangsan@example.com', '13800138001', 1),
('李四', 'lisi@example.com', '13800138002', 1),
('王五', 'wangwu@example.com', '13800138003', 1),
('赵六', 'zhaoliu@example.com', '13800138004', 0),
('钱七', 'qianqi@example.com', '13800138005', 1),
('孙八', 'sunba@example.com', '13800138006', 1),
('周九', 'zhoujiu@example.com', '13800138007', 0),
('吴十', 'wushi@example.com', '13800138008', 1);

-- 商品数据
INSERT INTO products (name, category_id, price, stock, status) VALUES
('iPhone 15 Pro', 1, 8999.00, 100, 1),
('MacBook Pro 14', 1, 14999.00, 50, 1),
('AirPods Pro 2', 2, 1799.00, 200, 1),
('iPad Air', 1, 4799.00, 80, 1),
('Apple Watch Series 9', 2, 2999.00, 120, 1),
('Sony WH-1000XM5', 2, 2499.00, 60, 1),
('Samsung Galaxy S24', 1, 6999.00, 90, 0),
('小米14 Pro', 1, 4999.00, 150, 1);

-- 订单数据（最近30天）
INSERT INTO orders (order_no, user_id, total_amount, status, created_at, paid_at) VALUES
('ORD20260501001', 1, 8999.00, 3, '2026-05-01 10:30:00', '2026-05-01 10:35:00'),
('ORD20260502001', 2, 16798.00, 3, '2026-05-02 14:20:00', '2026-05-02 14:25:00'),
('ORD20260503001', 3, 1799.00, 3, '2026-05-03 09:15:00', '2026-05-03 09:20:00'),
('ORD20260505001', 1, 4799.00, 2, '2026-05-05 16:45:00', '2026-05-05 16:50:00'),
('ORD20260507001', 4, 2999.00, 1, '2026-05-07 11:00:00', '2026-05-07 11:05:00'),
('ORD20260510001', 5, 14999.00, 3, '2026-05-10 13:30:00', '2026-05-10 13:35:00'),
('ORD20260512001', 2, 2499.00, 3, '2026-05-12 15:20:00', '2026-05-12 15:25:00'),
('ORD20260515001', 6, 4999.00, 2, '2026-05-15 10:10:00', '2026-05-15 10:15:00'),
('ORD20260518001', 1, 1799.00, 1, '2026-05-18 17:30:00', '2026-05-18 17:35:00'),
('ORD20260520001', 3, 8999.00, 3, '2026-05-20 08:45:00', '2026-05-20 08:50:00'),
('ORD20260522001', 7, 6999.00, 0, '2026-05-22 12:00:00', NULL),
('ORD20260525001', 2, 14999.00, 3, '2026-05-25 14:30:00', '2026-05-25 14:35:00'),
('ORD20260528001', 5, 2999.00, 3, '2026-05-28 09:20:00', '2026-05-28 09:25:00'),
('ORD20260530001', 8, 4999.00, 1, '2026-05-30 16:00:00', '2026-05-30 16:05:00'),
('ORD20260601001', 1, 2499.00, 3, '2026-06-01 11:30:00', '2026-06-01 11:35:00'),
('ORD20260602001', 3, 8999.00, 2, '2026-06-02 10:00:00', '2026-06-02 10:05:00'),
('ORD20260603001', 6, 1799.00, 4, '2026-06-03 15:45:00', NULL);

-- 订单明细数据
INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal) VALUES
(1, 1, 1, 8999.00, 8999.00),
(2, 2, 1, 14999.00, 14999.00),
(2, 3, 1, 1799.00, 1799.00),
(3, 3, 1, 1799.00, 1799.00),
(4, 4, 1, 4799.00, 4799.00),
(5, 5, 1, 2999.00, 2999.00),
(6, 2, 1, 14999.00, 14999.00),
(7, 6, 1, 2499.00, 2499.00),
(8, 8, 1, 4999.00, 4999.00),
(9, 3, 1, 1799.00, 1799.00),
(10, 1, 1, 8999.00, 8999.00),
(11, 7, 1, 6999.00, 6999.00),
(12, 2, 1, 14999.00, 14999.00),
(13, 5, 1, 2999.00, 2999.00),
(14, 8, 1, 4999.00, 4999.00),
(15, 6, 1, 2499.00, 2499.00),
(16, 1, 1, 8999.00, 8999.00),
(17, 3, 1, 1799.00, 1799.00);
