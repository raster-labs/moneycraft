import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import {
  CreateExtensionPackageRequest,
  CustomFieldDefDto,
  CustomFieldValueDto,
  EvaluatePreviewRequest,
  EvaluatePreviewResponse,
  ExtensionBindingDto,
  ExtensionPackageDto,
  ImportTdlProjectRequest,
  OdbcProjectionQueryRequest,
  OdbcProjectionQueryResponse,
  TdlImportProjectDto,
  UpsertCustomFieldDefRequest,
  UpsertCustomFieldValuesRequest,
} from './extension.models';

@Injectable({ providedIn: 'root' })
export class ExtensionService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);

  private extUrl(companyId: string, suffix: string): string {
    return this.runtimeConfig.url(
      `/companies/${encodeURIComponent(companyId)}/extensions${suffix}`,
    );
  }

  async listPackages(companyId: string): Promise<readonly ExtensionPackageDto[]> {
    return firstValueFrom(
      this.http.get<readonly ExtensionPackageDto[]>(this.extUrl(companyId, '/packages'), {
        withCredentials: true,
      }),
    );
  }

  async getPackage(companyId: string, packageId: string): Promise<ExtensionPackageDto> {
    return firstValueFrom(
      this.http.get<ExtensionPackageDto>(
        this.extUrl(companyId, `/packages/${encodeURIComponent(packageId)}`),
        { withCredentials: true },
      ),
    );
  }

  async createDraftPackage(
    companyId: string,
    payload: CreateExtensionPackageRequest,
  ): Promise<ExtensionPackageDto> {
    return firstValueFrom(
      this.http.post<ExtensionPackageDto>(this.extUrl(companyId, '/packages'), payload, {
        withCredentials: true,
      }),
    );
  }

  async validatePackage(companyId: string, packageId: string): Promise<ExtensionPackageDto> {
    return firstValueFrom(
      this.http.post<ExtensionPackageDto>(
        this.extUrl(companyId, `/packages/${encodeURIComponent(packageId)}/validate`),
        {},
        { withCredentials: true },
      ),
    );
  }

  async activatePackage(
    companyId: string,
    packageId: string,
    versionNo?: number,
  ): Promise<ExtensionBindingDto> {
    const headers =
      versionNo !== undefined ? new HttpHeaders({ 'If-Match': `"v${versionNo}"` }) : undefined;
    return firstValueFrom(
      this.http.post<ExtensionBindingDto>(
        this.extUrl(companyId, `/packages/${encodeURIComponent(packageId)}/activate`),
        {},
        { headers, withCredentials: true },
      ),
    );
  }

  async suspendPackage(
    companyId: string,
    packageId: string,
    versionNo?: number,
  ): Promise<ExtensionBindingDto> {
    const headers =
      versionNo !== undefined ? new HttpHeaders({ 'If-Match': `"v${versionNo}"` }) : undefined;
    return firstValueFrom(
      this.http.post<ExtensionBindingDto>(
        this.extUrl(companyId, `/packages/${encodeURIComponent(packageId)}/suspend`),
        {},
        { headers, withCredentials: true },
      ),
    );
  }

  async listBindings(companyId: string): Promise<readonly ExtensionBindingDto[]> {
    return firstValueFrom(
      this.http.get<readonly ExtensionBindingDto[]>(this.extUrl(companyId, '/bindings'), {
        withCredentials: true,
      }),
    );
  }

  async listCustomFields(
    companyId: string,
    ownerEntityType?: string,
  ): Promise<readonly CustomFieldDefDto[]> {
    let params = new HttpParams();
    if (ownerEntityType) {
      params = params.set('ownerEntityType', ownerEntityType);
    }
    return firstValueFrom(
      this.http.get<readonly CustomFieldDefDto[]>(this.extUrl(companyId, '/custom-fields'), {
        params,
        withCredentials: true,
      }),
    );
  }

  async createCustomField(
    companyId: string,
    payload: UpsertCustomFieldDefRequest,
  ): Promise<CustomFieldDefDto> {
    return firstValueFrom(
      this.http.post<CustomFieldDefDto>(this.extUrl(companyId, '/custom-fields'), payload, {
        withCredentials: true,
      }),
    );
  }

  async readCustomFieldValues(
    companyId: string,
    entityType: string,
    entityId: string,
    strictPrivateAccess = false,
  ): Promise<readonly CustomFieldValueDto[]> {
    const params = new HttpParams().set('strictPrivateAccess', String(strictPrivateAccess));
    return firstValueFrom(
      this.http.get<readonly CustomFieldValueDto[]>(
        this.extUrl(
          companyId,
          `/custom-fields/values/${encodeURIComponent(entityType)}/${encodeURIComponent(entityId)}`,
        ),
        { params, withCredentials: true },
      ),
    );
  }

  async writeCustomFieldValues(
    companyId: string,
    entityType: string,
    entityId: string,
    payload: UpsertCustomFieldValuesRequest,
  ): Promise<readonly CustomFieldValueDto[]> {
    return firstValueFrom(
      this.http.put<readonly CustomFieldValueDto[]>(
        this.extUrl(
          companyId,
          `/custom-fields/values/${encodeURIComponent(entityType)}/${encodeURIComponent(entityId)}`,
        ),
        payload,
        { withCredentials: true },
      ),
    );
  }

  async evaluatePreview(
    companyId: string,
    payload: EvaluatePreviewRequest,
  ): Promise<EvaluatePreviewResponse> {
    return firstValueFrom(
      this.http.post<EvaluatePreviewResponse>(this.extUrl(companyId, '/evaluate-preview'), payload, {
        withCredentials: true,
      }),
    );
  }

  async listTdlImports(companyId: string): Promise<readonly TdlImportProjectDto[]> {
    return firstValueFrom(
      this.http.get<readonly TdlImportProjectDto[]>(this.extUrl(companyId, '/tdl-imports'), {
        withCredentials: true,
      }),
    );
  }

  async getTdlImport(companyId: string, projectId: string): Promise<TdlImportProjectDto> {
    return firstValueFrom(
      this.http.get<TdlImportProjectDto>(
        this.extUrl(companyId, `/tdl-imports/${encodeURIComponent(projectId)}`),
        { withCredentials: true },
      ),
    );
  }

  async importTdlProject(
    companyId: string,
    payload: ImportTdlProjectRequest,
  ): Promise<TdlImportProjectDto> {
    return firstValueFrom(
      this.http.post<TdlImportProjectDto>(this.extUrl(companyId, '/tdl-imports'), payload, {
        withCredentials: true,
      }),
    );
  }

  async executeOdbcProjectionQuery(
    companyId: string,
    payload: OdbcProjectionQueryRequest,
  ): Promise<OdbcProjectionQueryResponse> {
    return firstValueFrom(
      this.http.post<OdbcProjectionQueryResponse>(this.extUrl(companyId, '/odbc-query'), payload, {
        withCredentials: true,
      }),
    );
  }
}
