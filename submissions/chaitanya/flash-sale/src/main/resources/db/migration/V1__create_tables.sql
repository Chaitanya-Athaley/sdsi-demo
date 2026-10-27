CREATE TABLE products (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    sku             VARCHAR(50)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    initial_stock   INT          NOT NULL,
    available_stock INT          NOT NULL,
    reserved_stock  INT          NOT NULL,
    sold_stock      INT          NOT NULL,
    purchase_limit  INT          NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT ck_initial_stock_nonnegative CHECK (initial_stock >= 0),
    CONSTRAINT ck_available_stock_nonnegative CHECK (available_stock >= 0),
    CONSTRAINT ck_reserved_stock_nonnegative CHECK (reserved_stock >= 0),
    CONSTRAINT ck_sold_stock_nonnegative CHECK (sold_stock >= 0),
    CONSTRAINT ck_purchase_limit_nonnegative CHECK (purchase_limit >= 0)
);

CREATE TABLE reservations (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id      VARCHAR(100) NOT NULL,
    sku          VARCHAR(50)  NOT NULL,
    quantity     INT          NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'RESERVED',
    created_at   TIMESTAMP    NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    confirmed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_sku_fk FOREIGN KEY (sku) REFERENCES products(sku)
);

CREATE INDEX idx_reservations_sku ON reservations(sku);
CREATE INDEX idx_reservations_user_sku ON reservations(user_id, sku);
CREATE INDEX idx_reservations_expires_at_status ON reservations(expires_at, status);