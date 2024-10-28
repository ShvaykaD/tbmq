--
-- Copyright © 2016-2024 The Thingsboard Authors
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

CREATE TABLE IF NOT EXISTS ts_kv_latest (
    entity_id varchar (255) NOT NULL,
    key int NOT NULL,
    ts bigint NOT NULL,
    long_v bigint,
    CONSTRAINT ts_kv_latest_pkey PRIMARY KEY (entity_id, key)
);

CREATE TABLE IF NOT EXISTS unauthorized_client (
    client_id varchar(255) NOT NULL CONSTRAINT unauthorized_clients_pkey PRIMARY KEY,
    ip_address varchar(255) NOT NULL,
    ts bigint NOT NULL,
    username varchar(255),
    password_provided boolean,
    tls_used boolean,
    reason varchar
);
