///
/// Copyright © 2016-2024 The Thingsboard Authors
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

import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { Authority } from '@shared/models/authority.enum';

const routes: Routes = [
  {
    path: 'subscriptions',
    loadComponent: () => import('@home/pages/subscriptions/subscriptions-table.component').then(m => m.SubscriptionsTableComponent),
    data: {
      auth: [Authority.SYS_ADMIN],
      title: 'subscription.subscriptions',
      breadcrumb: {
        label: 'subscription.subscriptions',
        icon: 'mdi:filter-outline'
      }
    }
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})

export class SubscriptionsRoutingModule {
}
