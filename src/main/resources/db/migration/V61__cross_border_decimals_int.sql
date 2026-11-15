-- Hibernate expects an INTEGER here (V60 created it as SMALLINT and the startup schema check refused it).
ALTER TABLE cross_border_corridors ALTER COLUMN receive_decimals TYPE INTEGER;
