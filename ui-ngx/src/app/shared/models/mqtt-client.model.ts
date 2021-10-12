///
/// Copyright © 2016-2020 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { BaseData } from '@shared/models/base-data';
import { DetailedClientSessionInfo } from '@shared/models/mqtt-session.model';
import { ClientId } from '@shared/models/id/mqtt-client.id';

export interface Client extends ClientInfo, DetailedClientSessionInfo, BaseData<ClientId> {
}

export interface ClientInfo {
  clientId: string;
  type: ClientType;
}

export enum ClientType {
  DEVICE = 'DEVICE',
  APPLICATION = 'APPLICATION'
}

export const clientTypeTranslationMap = new Map<ClientType, string>(
  [
    [ClientType.DEVICE, 'mqtt-client.type-device'],
    [ClientType.APPLICATION, 'mqtt-client.type-application']
  ]
);
