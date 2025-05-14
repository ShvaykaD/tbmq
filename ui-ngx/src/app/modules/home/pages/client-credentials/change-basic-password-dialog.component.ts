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

import { Component, Inject, OnInit } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogClose, MatDialogContent, MatDialogActions } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { UntypedFormBuilder, UntypedFormGroup, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { TranslateService, TranslateModule } from '@ngx-translate/core';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import { ClientCredentialsService } from '@core/http/client-credentials.service';
import { MatToolbar } from '@angular/material/toolbar';
import { MatIconButton, MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { AsyncPipe } from '@angular/common';
import { MatProgressBar } from '@angular/material/progress-bar';
import { MatFormField, MatLabel, MatPrefix, MatSuffix } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { TogglePasswordComponent } from '@shared/components/button/toggle-password.component';

export interface ChangeBasicPasswordDialogData {
  credentialsId: string;
}

@Component({
    selector: 'tb-change-basic-password-dialog',
    templateUrl: './change-basic-password-dialog.component.html',
    styleUrls: ['./change-basic-password-dialog.component.scss'],
    imports: [FormsModule, ReactiveFormsModule, MatToolbar, TranslateModule, MatIconButton, MatDialogClose, MatIcon, MatProgressBar, MatDialogContent, MatFormField, MatLabel, MatInput, MatPrefix, TogglePasswordComponent, MatSuffix, MatDialogActions, MatButton, AsyncPipe]
})
export class ChangeBasicPasswordDialogComponent extends DialogComponent<ChangeBasicPasswordDialogComponent,
  ChangeBasicPasswordDialogData> implements OnInit {

  changePassword: UntypedFormGroup;
  credentialsId = this.data.credentialsId;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              private translate: TranslateService,
              public dialogRef: MatDialogRef<ChangeBasicPasswordDialogComponent>,
              @Inject(MAT_DIALOG_DATA) public data: ChangeBasicPasswordDialogData,
              public fb: UntypedFormBuilder,
              private clientCredentialsService: ClientCredentialsService) {
    super(store, router, dialogRef);
  }

  ngOnInit(): void {
    this.buildChangePasswordForm();
  }

  buildChangePasswordForm() {
    this.changePassword = this.fb.group({
      currentPassword: [null],
      newPassword: [''],
      newPassword2: ['']
    });
  }

  onChangePassword(): void {
    if (this.changePassword.get('newPassword').value !== this.changePassword.get('newPassword2').value) {
      this.store.dispatch(new ActionNotificationShow({
        message: this.translate.instant('login.passwords-mismatch-error'),
        type: 'error'
      }));
    } else {
      this.clientCredentialsService.changePassword(
        this.changePassword.get('currentPassword').value,
        this.changePassword.get('newPassword').value,
        this.credentialsId).subscribe(
        (credentials) => {
          this.store.dispatch(new ActionNotificationShow({
            message: this.translate.instant('mqtt-client-credentials.password-changed'),
            type: 'success',
            duration: 2000
          }));
          this.dialogRef.close(credentials);
        });
    }
  }
}
