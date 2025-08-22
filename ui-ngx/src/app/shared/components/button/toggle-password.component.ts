///
/// Copyright © 2016-2025 The Thingsboard Authors
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

import { AfterViewInit, Component, ElementRef, input } from '@angular/core';

import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';

@Component({
    selector: 'tb-toggle-password',
    templateUrl: 'toggle-password.component.html',
    imports: [MatIconButton, MatIcon]
})
export class TogglePasswordComponent implements AfterViewInit {

  disabled = input<boolean>(false);
  showPassword = false;
  hideToggle = false;

  private input: HTMLInputElement = null;

  constructor(private hostElement: ElementRef) { }

  togglePassword($event: Event) {
    $event.stopPropagation();
    this.showPassword = !this.showPassword;
    this.input.type = this.showPassword ? 'text' : 'password';
  }

  ngAfterViewInit() {
    this.input = this.hostElement.nativeElement.closest('mat-form-field').querySelector('input[type="password"]');
    if (this.input === null) {
      this.hideToggle = true;
    }
  }
}
