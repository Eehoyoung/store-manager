-- JPA 문자열 매핑과 일치시킨다. 값은 항상 64자 SHA-256 hex이며 UNIQUE 제약은 유지된다.
ALTER TABLE franchise_join_code ALTER COLUMN code_hash TYPE VARCHAR(64);
