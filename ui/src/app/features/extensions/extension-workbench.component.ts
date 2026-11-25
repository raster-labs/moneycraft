import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';
import {
  CustomFieldDataType,
  CustomFieldDefDto,
  CustomFieldEntityType,
  CustomFieldProtectionClass,
  CustomFieldValueDto,
  DmnHitPolicy,
  EvaluatePreviewResponse,
  ExtensionBindingDto,
  ExtensionPackageDto,
  ExtensionWorkbenchTab,
  JSON_SCHEMA_2020_12,
  OdbcProjectionName,
  OdbcProjectionQueryResponse,
  TdlImportProjectDto,
} from './extension.models';
import { ExtensionService } from './extension.service';

@Component({
  selector: 'mc-extension-workbench',
  standalone: true,
  imports: [FormsModule, CodeBadgeComponent, SemanticIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-ext-shell" [class.mc-ext-embedded]="embeddedOverlay()">
      <header class="mc-ext-header mc-card">
        <div>
          <div class="mc-ext-heading-row">
            <h1 class="mc-ext-title">
              Visual Extension Workbench &amp; Static .tdl Translation Matrix
            </h1>
            <span class="mc-badge mc-badge-jade">MC-EXTENSION-PACKAGE-1</span>
          </div>
          <p class="mc-ext-subtitle">
            Declarative custom fields (STANDARD &amp; SELECTED_PRIVATE), bounded FEEL / DMN rules,
            static .tdl analysis (zero runtime execution), and explicit company activation
          </p>
        </div>

        <div class="mc-ext-tabs" role="tablist" aria-label="Extension Workbench Tabs">
          <button
            type="button"
            role="tab"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'CUSTOM_FIELDS'"
            [attr.aria-selected]="activeTab() === 'CUSTOM_FIELDS'"
            data-testid="ext-tab-custom-fields"
            (click)="switchTab('CUSTOM_FIELDS')"
          >
            <mc-icon name="ledger" [size]="16" />
            <span>Custom Fields &amp; Anchors</span>
          </button>
          <button
            type="button"
            role="tab"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'RULES_PACKAGES'"
            [attr.aria-selected]="activeTab() === 'RULES_PACKAGES'"
            data-testid="ext-tab-rules"
            (click)="switchTab('RULES_PACKAGES')"
          >
            <mc-icon name="approve-shield" [size]="16" />
            <span>FEEL / DMN Rules &amp; Packages</span>
          </button>
          <button
            type="button"
            role="tab"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'TDL_IMPORTER'"
            [attr.aria-selected]="activeTab() === 'TDL_IMPORTER'"
            data-testid="ext-tab-tdl"
            (click)="switchTab('TDL_IMPORTER')"
          >
            <mc-icon name="import" [size]="16" />
            <span>Static .tdl Importer &amp; Parity Matrix</span>
          </button>
          <button
            type="button"
            role="tab"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'LIFECYCLE_ODBC'"
            [attr.aria-selected]="activeTab() === 'LIFECYCLE_ODBC'"
            data-testid="ext-tab-lifecycle"
            (click)="switchTab('LIFECYCLE_ODBC')"
          >
            <mc-icon name="post-ledger" [size]="16" />
            <span>Activation &amp; Projection Query</span>
          </button>
        </div>
      </header>

      @if (errorBanner(); as err) {
        <div class="mc-ext-alert mc-ext-alert-error" role="alert">
          <mc-icon name="error" [size]="20" />
          <span>{{ err }}</span>
        </div>
      }
      @if (statusBanner(); as msg) {
        <div class="mc-ext-alert mc-ext-alert-ok" role="status" data-testid="ext-status-banner">
          <mc-icon name="confirmed" [size]="20" />
          <span>{{ msg }}</span>
        </div>
      }

      <!-- ================================================================= -->
      <!-- TAB 1: CUSTOM FIELDS & SEMANTIC SCREEN ANCHORS                    -->
      <!-- ================================================================= -->
      @if (activeTab() === 'CUSTOM_FIELDS') {
        <section class="mc-card mc-ext-card" aria-label="Create Custom Field Definition">
          <div class="mc-ext-section-head">
            <span>Define Custom Field &amp; Semantic Screen Anchor</span>
            <span class="mc-code">EXT-001 / EXT-002 / EXT-003</span>
          </div>
          <form class="mc-ext-body-stack" (ngSubmit)="createFieldDefinition()">
            <div class="mc-ext-grid-4">
              <div>
                <label class="mc-field-label" for="mc-cf-ns">Namespace (Reverse-DNS)</label>
                <input
                  id="mc-cf-ns"
                  type="text"
                  name="cfNamespace"
                  class="mc-input mc-code"
                  data-testid="ext-cf-namespace"
                  [(ngModel)]="cfNamespace"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-cf-entity">Owner Entity</label>
                <select
                  id="mc-cf-entity"
                  name="cfEntityType"
                  class="mc-select"
                  data-testid="ext-cf-entity"
                  [(ngModel)]="cfEntityType"
                >
                  <option value="VOUCHER">VOUCHER</option>
                  <option value="LEDGER">LEDGER</option>
                  <option value="STOCK_ITEM">STOCK_ITEM</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-cf-code">Field Code</label>
                <input
                  id="mc-cf-code"
                  type="text"
                  name="cfFieldCode"
                  class="mc-input mc-code"
                  data-testid="ext-cf-code"
                  [(ngModel)]="cfFieldCode"
                  placeholder="vehicle_no"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-cf-label">Display Label</label>
                <input
                  id="mc-cf-label"
                  type="text"
                  name="cfLabel"
                  class="mc-input"
                  data-testid="ext-cf-label"
                  [(ngModel)]="cfLabel"
                  placeholder="Vehicle Registration No"
                  required
                />
              </div>
            </div>

            <div class="mc-ext-grid-4">
              <div>
                <label class="mc-field-label" for="mc-cf-dtype">Data Type</label>
                <select
                  id="mc-cf-dtype"
                  name="cfDataType"
                  class="mc-select"
                  data-testid="ext-cf-datatype"
                  [(ngModel)]="cfDataType"
                >
                  <option value="TEXT">TEXT</option>
                  <option value="EXACT_DECIMAL">EXACT_DECIMAL</option>
                  <option value="DATE">DATE</option>
                  <option value="BOOLEAN">BOOLEAN</option>
                  <option value="ENUM">ENUM</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-cf-prot">Protection Class</label>
                <select
                  id="mc-cf-prot"
                  name="cfProtectionClass"
                  class="mc-select"
                  data-testid="ext-cf-protection"
                  [(ngModel)]="cfProtectionClass"
                >
                  <option value="STANDARD">STANDARD (Normal Audit &amp; Export)</option>
                  <option value="SELECTED_PRIVATE">
                    SELECTED_PRIVATE (AES-256-GCM Encrypted + Redacted)
                  </option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-cf-anchor">Semantic UI Anchor</label>
                <select
                  id="mc-cf-anchor"
                  name="cfUiAnchor"
                  class="mc-select mc-code"
                  data-testid="ext-cf-anchor"
                  [(ngModel)]="cfUiAnchor"
                >
                  <option value="voucher.header.extra">voucher.header.extra</option>
                  <option value="voucher.line.extra">voucher.line.extra</option>
                  <option value="ledger.profile.extra">ledger.profile.extra</option>
                  <option value="stockItem.compliance.extra">stockItem.compliance.extra</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-cf-extra">
                  {{ cfDataType === 'ENUM' ? 'Enum Options (comma-separated)' : 'Decimal Scale' }}
                </label>
                @if (cfDataType === 'ENUM') {
                  <input
                    id="mc-cf-extra"
                    type="text"
                    name="cfEnumCsv"
                    class="mc-input mc-code"
                    [(ngModel)]="cfEnumCsv"
                    placeholder="ROAD,RAIL,AIR"
                  />
                } @else {
                  <input
                    id="mc-cf-extra"
                    type="number"
                    name="cfDecimalScale"
                    min="0"
                    max="6"
                    class="mc-input mc-code"
                    [(ngModel)]="cfDecimalScale"
                  />
                }
              </div>
            </div>

            <div class="mc-ext-bar">
              <label class="mc-ext-check">
                <input type="checkbox" name="cfRequiredFlag" [(ngModel)]="cfRequiredFlag" />
                <span>Mandatory field when extension is ACTIVE</span>
              </label>

              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="ext-cf-create-btn"
                [disabled]="
                  busy() ||
                  !cfNamespace.trim() ||
                  !cfFieldCode.trim() ||
                  !cfLabel.trim() ||
                  companyContext.quarantined()
                "
              >
                <mc-icon name="save-document" [size]="16" />
                <span>Save Custom Field</span>
              </button>
            </div>
          </form>
        </section>

        <section class="mc-card mc-ext-card" aria-label="Registered Custom Fields">
          <div class="mc-ext-section-head">
            <span>Registered Custom Fields ({{ customFields().length }})</span>
            <button type="button" class="mc-btn" (click)="reloadCustomFields()">
              <mc-icon name="refresh" [size]="16" />
              <span>Refresh</span>
            </button>
          </div>
          <div class="mc-ext-table-wrap">
            <table class="mc-ext-table">
              <thead>
                <tr>
                  <th scope="col">Namespace :: Code</th>
                  <th scope="col">Entity</th>
                  <th scope="col">Label</th>
                  <th scope="col">Type</th>
                  <th scope="col">Protection</th>
                  <th scope="col">UI Anchor</th>
                </tr>
              </thead>
              <tbody>
                @for (cf of customFields(); track cf.id) {
                  <tr>
                    <td class="mc-code">{{ cf.namespace }}::{{ cf.fieldCode }}</td>
                    <td><span class="mc-badge mc-badge-slate">{{ cf.ownerEntityType }}</span></td>
                    <td>{{ cf.label }}</td>
                    <td class="mc-code">{{ cf.dataType }}</td>
                    <td>
                      <span
                        class="mc-badge"
                        [class.mc-badge-jade]="cf.protectionClass === 'STANDARD'"
                        [class.mc-badge-warn]="cf.protectionClass === 'SELECTED_PRIVATE'"
                      >
                        {{ cf.protectionClass }}
                      </span>
                    </td>
                    <td class="mc-code">{{ cf.uiAnchor }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="6" class="mc-empty-records">No matching records</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>

        <section class="mc-card mc-ext-card" aria-label="Entity Custom Field Value Inspector">
          <div class="mc-ext-section-head">
            <span>Entity Custom Field Values Reader / Writer</span>
          </div>
          <div class="mc-ext-body-stack">
            <div class="mc-ext-grid-4">
              <div>
                <label class="mc-field-label" for="mc-val-ent">Entity Type</label>
                <select id="mc-val-ent" class="mc-select" [(ngModel)]="valEntityType">
                  <option value="VOUCHER">VOUCHER</option>
                  <option value="LEDGER">LEDGER</option>
                  <option value="STOCK_ITEM">STOCK_ITEM</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-val-id">Entity UUID</label>
                <input
                  id="mc-val-id"
                  type="text"
                  class="mc-input mc-code"
                  [(ngModel)]="valEntityId"
                  placeholder="00000000-0000-0000-0000-000000000001"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-val-code">Field Code</label>
                <input
                  id="mc-val-code"
                  type="text"
                  class="mc-input mc-code"
                  [(ngModel)]="valFieldCode"
                  placeholder="vehicle_no"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-val-text">Field Value</label>
                <input id="mc-val-text" type="text" class="mc-input" [(ngModel)]="valFieldValue" />
              </div>
            </div>
            <div class="mc-ext-bar">
              <label class="mc-ext-check">
                <input type="checkbox" [(ngModel)]="valStrictPrivate" />
                <span>Strict Private Access check on read</span>
              </label>
              <div class="mc-ext-inline-row">
                <button
                  type="button"
                  class="mc-btn"
                  [disabled]="busy() || !valEntityId.trim()"
                  (click)="loadEntityCustomValues()"
                >
                  <mc-icon name="search" [size]="16" />
                  <span>Read Values</span>
                </button>
                <button
                  type="button"
                  class="mc-btn mc-btn-primary"
                  [disabled]="
                    busy() || !valEntityId.trim() || !valFieldCode.trim() || companyContext.quarantined()
                  "
                  (click)="saveEntityCustomValue()"
                >
                  <mc-icon name="save-document" [size]="16" />
                  <span>Write Value</span>
                </button>
              </div>
            </div>

            @if (entityFieldValues().length > 0) {
              <div class="mc-ext-table-wrap">
                <table class="mc-ext-table">
                  <thead>
                    <tr>
                      <th scope="col">Field</th>
                      <th scope="col">Protection</th>
                      <th scope="col">Value</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (fv of entityFieldValues(); track fv.fieldDefId) {
                      <tr>
                        <td class="mc-code">{{ fv.namespace }}::{{ fv.fieldCode }}</td>
                        <td>{{ fv.protectionClass }}</td>
                        <td class="mc-code">{{ fv.redacted ? '[REDACTED]' : fv.value }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </section>
      }

      <!-- ================================================================= -->
      <!-- TAB 2: BOUNDED FEEL / DMN RULES & EXTENSION PACKAGE BUILDER       -->
      <!-- ================================================================= -->
      @if (activeTab() === 'RULES_PACKAGES') {
        <div class="mc-ext-split-grid">
          <section class="mc-card mc-ext-card" aria-label="Bounded FEEL and DMN Live Evaluator">
            <div class="mc-ext-section-head">
              <span>Bounded FEEL / DMN Decision Table Live Preview</span>
              <span class="mc-code">EXT-004</span>
            </div>
            <div class="mc-ext-body-stack">
              <div>
                <label class="mc-field-label" for="mc-feel-expr">
                  Bounded FEEL Expression (Zero Reflection, Exact BigDecimal)
                </label>
                <input
                  id="mc-feel-expr"
                  type="text"
                  class="mc-input mc-code"
                  data-testid="ext-feel-expression"
                  [(ngModel)]="previewExpression"
                />
              </div>
              <div class="mc-ext-grid-2">
                <div>
                  <label class="mc-field-label" for="mc-dmn-policy">DMN Hit Policy</label>
                  <select id="mc-dmn-policy" class="mc-select" [(ngModel)]="dmnHitPolicy">
                    <option value="UNIQUE">UNIQUE</option>
                    <option value="FIRST">FIRST</option>
                    <option value="COLLECT">COLLECT</option>
                  </select>
                </div>
                <div>
                  <label class="mc-field-label" for="mc-feel-inputs">Sample Inputs (JSON)</label>
                  <input
                    id="mc-feel-inputs"
                    type="text"
                    class="mc-input mc-code"
                    data-testid="ext-feel-inputs"
                    [(ngModel)]="previewInputsJson"
                  />
                </div>
              </div>

              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="ext-feel-eval-btn"
                [disabled]="busy() || !previewExpression.trim()"
                (click)="runFeelPreview()"
              >
                <mc-icon name="approve-shield" [size]="16" />
                <span>Evaluate Bounded Rule</span>
              </button>

              @if (previewResult(); as res) {
                <div class="mc-ext-preview-box" data-testid="ext-feel-result">
                  <div class="mc-ext-inline-row">
                    <span
                      class="mc-badge"
                      [class.mc-badge-jade]="res.valid"
                      [class.mc-badge-danger]="!res.valid"
                    >
                      {{ res.valid ? 'VALID' : 'REJECTED' }}
                    </span>
                    <span class="mc-code">Type: {{ res.resultType }}</span>
                    <span class="mc-code">Result: {{ res.resultValue }}</span>
                    <span class="mc-code">
                      AST Depth: {{ res.astDepth }} | Steps: {{ res.stepsConsumed }}
                    </span>
                  </div>
                  @if (res.messages.length > 0) {
                    <div class="mc-code">{{ res.messages.join(' | ') }}</div>
                  }
                </div>
              }
            </div>
          </section>

          <section class="mc-card mc-ext-card" aria-label="Create Extension Package">
            <div class="mc-ext-section-head">
              <span>Create Draft Extension Package (JSON Schema 2020-12)</span>
              <span class="mc-code">EXT-001</span>
            </div>
            <div class="mc-ext-body-stack">
              <div class="mc-ext-grid-2">
                <div>
                  <label class="mc-field-label" for="mc-pkg-ns">Package Namespace</label>
                  <input
                    id="mc-pkg-ns"
                    type="text"
                    class="mc-input mc-code"
                    data-testid="ext-pkg-namespace"
                    [(ngModel)]="pkgNamespace"
                  />
                </div>
                <div>
                  <label class="mc-field-label" for="mc-pkg-name">Display Name</label>
                  <input
                    id="mc-pkg-name"
                    type="text"
                    class="mc-input"
                    data-testid="ext-pkg-name"
                    [(ngModel)]="pkgDisplayName"
                  />
                </div>
              </div>
              <div class="mc-ext-grid-2">
                <div>
                  <label class="mc-field-label" for="mc-pkg-rule-code">FEEL Validation Rule Code</label>
                  <input
                    id="mc-pkg-rule-code"
                    type="text"
                    class="mc-input mc-code"
                    [(ngModel)]="pkgRuleCode"
                  />
                </div>
                <div>
                  <label class="mc-field-label" for="mc-pkg-rule-msg">Validation Failure Message</label>
                  <input
                    id="mc-pkg-rule-msg"
                    type="text"
                    class="mc-input"
                    [(ngModel)]="pkgRuleErrorMessage"
                  />
                </div>
              </div>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="ext-pkg-create-btn"
                [disabled]="
                  busy() || !pkgNamespace.trim() || !pkgDisplayName.trim() || companyContext.quarantined()
                "
                (click)="createDraftExtensionPackage()"
              >
                <mc-icon name="save-document" [size]="16" />
                <span>Create Inactive DRAFT Package</span>
              </button>
            </div>
          </section>
        </div>
      }

      <!-- ================================================================= -->
      <!-- TAB 3: STATIC .TDL IMPORTER & 5-DISPOSITION PARITY MATRIX         -->
      <!-- ================================================================= -->
      @if (activeTab() === 'TDL_IMPORTER') {
        <section class="mc-card mc-ext-card" aria-label="Static TDL Project Importer">
          <div class="mc-ext-section-head">
            <span>Static .tdl / .txt Source Importer (Strictly Zero TDL Runtime Execution)</span>
            <span class="mc-code">TDL-001 .. TDL-004</span>
          </div>
          <div class="mc-ext-body-stack">
            <div class="mc-ext-grid-4">
              <div>
                <label class="mc-field-label" for="mc-tdl-upload">Upload .tdl / .txt Source</label>
                <input
                  id="mc-tdl-upload"
                  type="file"
                  accept=".tdl,.txt"
                  class="mc-input"
                  data-testid="ext-tdl-file-input"
                  (change)="onTdlFileSelected($event)"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-tdl-proj">Project Name</label>
                <input
                  id="mc-tdl-proj"
                  type="text"
                  class="mc-input"
                  data-testid="ext-tdl-project-name"
                  [(ngModel)]="tdlProjectName"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-tdl-fname">Primary File Name</label>
                <input
                  id="mc-tdl-fname"
                  type="text"
                  class="mc-input mc-code"
                  [(ngModel)]="tdlFileName"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-tdl-ver">Source Version Claim</label>
                <input
                  id="mc-tdl-ver"
                  type="text"
                  class="mc-input mc-code"
                  [(ngModel)]="tdlVersionClaim"
                />
              </div>
            </div>

            <div>
              <label class="mc-field-label" for="mc-tdl-src">Static .tdl Source Text</label>
              <textarea
                id="mc-tdl-src"
                rows="6"
                class="mc-input mc-code"
                data-testid="ext-tdl-textarea"
                [(ngModel)]="tdlSourceText"
                placeholder="[System: UDF]&#10;VehicleRegNo : String : 1001&#10;&#10;[Field: VchVehicleField]&#10;Use : Name Field&#10;Storage : VehicleRegNo"
              ></textarea>
            </div>

            <div class="mc-ext-bar">
              <span class="mc-code">
                Generates an inactive DRAFT package + 5-disposition Functional Parity Matrix.
              </span>
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="ext-tdl-import-btn"
                [disabled]="
                  busy() || !tdlSourceText.trim() || !tdlProjectName.trim() || companyContext.quarantined()
                "
                (click)="analyzeTdlProject()"
              >
                <mc-icon name="import" [size]="16" />
                <span>Analyze .tdl &amp; Build Parity Matrix</span>
              </button>
            </div>
          </div>
        </section>

        @if (activeTdlProject(); as proj) {
          <section
            class="mc-card mc-ext-card"
            data-testid="ext-tdl-matrix-card"
            aria-label="TDL Functional Parity Matrix"
          >
            <div class="mc-ext-section-head">
              <div class="mc-ext-inline-row">
                <span>Functional Parity Matrix — {{ proj.projectName }} ({{ proj.fileName }})</span>
                <span class="mc-badge mc-badge-slate">{{ proj.status }}</span>
                @if (proj.hasCompiledTcpBlock) {
                  <span class="mc-badge mc-badge-danger">BLOCKED: Compiled .tcp Binary</span>
                }
                @if (proj.hasNativeDllBlock) {
                  <span class="mc-badge mc-badge-danger">UNSUPPORTED: Native DLL / COM Call</span>
                }
                @if (proj.hasUnresolvedIncludes) {
                  <span class="mc-badge mc-badge-warn">BLOCKED: Unresolved Include</span>
                }
              </div>

              <div class="mc-ext-inline-row">
                <select
                  class="mc-select"
                  aria-label="Filter Parity Disposition"
                  data-testid="ext-tdl-disposition-filter"
                  [ngModel]="tdlDispositionFilter()"
                  (ngModelChange)="tdlDispositionFilter.set($event)"
                >
                  <option value="ALL">All 5 Dispositions ({{ proj.parityMatrix.length }})</option>
                  <option value="TRANSLATED_VERIFICATION_PENDING">
                    TRANSLATED_VERIFICATION_PENDING
                  </option>
                  <option value="REQUIRES_MAPPING">REQUIRES_MAPPING</option>
                  <option value="UNSUPPORTED">UNSUPPORTED</option>
                  <option value="BLOCKED_DEPENDENCY">BLOCKED_DEPENDENCY</option>
                  <option value="NOT_APPLICABLE_WITH_EXPLANATION">
                    NOT_APPLICABLE_WITH_EXPLANATION
                  </option>
                </select>
                @if (proj.draftExtensionId) {
                  <button
                    type="button"
                    class="mc-btn"
                    (click)="switchTab('LIFECYCLE_ODBC')"
                  >
                    <mc-icon name="details" [size]="16" />
                    <span>Inspect Draft Package</span>
                  </button>
                }
              </div>
            </div>

            <div class="mc-ext-meta-bar">
              <span>Source SHA-256: <mc-code-badge [code]="proj.sourceSha256Hex" /></span>
              @if (proj.draftExtensionId; as draftId) {
                <span>Draft Package ID: <mc-code-badge [code]="draftId" /></span>
              }
            </div>

            <div class="mc-ext-table-wrap">
              <table class="mc-ext-table" aria-label="TDL Construct Dispositions">
                <thead>
                  <tr>
                    <th scope="col">#</th>
                    <th scope="col">Source Coordinates</th>
                    <th scope="col">Construct</th>
                    <th scope="col">Required Behaviour</th>
                    <th scope="col">Target Component</th>
                    <th scope="col">Disposition</th>
                    <th scope="col">Verification Status</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of filteredParityRows(); track row.rowNo) {
                    <tr>
                      <td class="mc-code">{{ row.rowNo }}</td>
                      <td class="mc-code">{{ row.sourceRef }}</td>
                      <td class="mc-code">{{ row.constructIdentifier }}</td>
                      <td>{{ row.requiredBehaviour }}</td>
                      <td class="mc-code">{{ row.targetComponent }}</td>
                      <td>
                        <span
                          class="mc-badge"
                          [class.mc-badge-jade]="
                            row.disposition === 'TRANSLATED_VERIFICATION_PENDING'
                          "
                          [class.mc-badge-warn]="
                            row.disposition === 'REQUIRES_MAPPING' ||
                            row.disposition === 'NOT_APPLICABLE_WITH_EXPLANATION'
                          "
                          [class.mc-badge-danger]="
                            row.disposition === 'UNSUPPORTED' ||
                            row.disposition === 'BLOCKED_DEPENDENCY'
                          "
                        >
                          {{ row.disposition }}
                        </span>
                      </td>
                      <td class="mc-code">{{ row.executionStatus }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="7" class="mc-empty-records">No matching records</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }
      }

      <!-- ================================================================= -->
      <!-- TAB 4: COMPANY EXTENSION ACTIVATION & READ-ONLY ODBC PROJECTION   -->
      <!-- ================================================================= -->
      @if (activeTab() === 'LIFECYCLE_ODBC') {
        <section class="mc-card mc-ext-card" aria-label="Extension Package Lifecycle">
          <div class="mc-ext-section-head">
            <span>Extension Packages — Validate, Activate &amp; Suspend</span>
            <span class="mc-code">EXT-002 / EXT-008</span>
          </div>
          <div class="mc-ext-table-wrap">
            <table class="mc-ext-table">
              <thead>
                <tr>
                  <th scope="col">Namespace (Rev)</th>
                  <th scope="col">Display Name</th>
                  <th scope="col">Package Status</th>
                  <th scope="col">Company Binding</th>
                  <th scope="col">Definition SHA-256</th>
                  <th scope="col">Lifecycle Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (pkg of packages(); track pkg.id) {
                  <tr>
                    <td class="mc-code">{{ pkg.namespace }} (r{{ pkg.revisionNo }})</td>
                    <td>{{ pkg.displayName }}</td>
                    <td><span class="mc-badge mc-badge-slate">{{ pkg.status }}</span></td>
                    <td>
                      <span
                        class="mc-badge"
                        [class.mc-badge-jade]="pkg.bindingState === 'ACTIVE'"
                        [class.mc-badge-warn]="pkg.bindingState === 'SUSPENDED'"
                      >
                        {{ pkg.bindingState || 'INACTIVE' }}
                      </span>
                    </td>
                    <td><mc-code-badge [code]="pkg.definitionSha256Hex.slice(0, 16)" /></td>
                    <td>
                      <div class="mc-ext-inline-row">
                        <button
                          type="button"
                          class="mc-btn"
                          data-testid="ext-pkg-validate-btn"
                          [disabled]="busy() || companyContext.quarantined()"
                          (click)="validatePkg(pkg)"
                        >
                          Validate
                        </button>
                        <button
                          type="button"
                          class="mc-btn mc-btn-primary"
                          data-testid="ext-pkg-activate-btn"
                          [disabled]="
                            busy() || pkg.bindingState === 'ACTIVE' || companyContext.quarantined()
                          "
                          (click)="activatePkg(pkg)"
                        >
                          Activate
                        </button>
                        <button
                          type="button"
                          class="mc-btn mc-btn-danger"
                          data-testid="ext-pkg-suspend-btn"
                          [disabled]="
                            busy() || pkg.bindingState !== 'ACTIVE' || companyContext.quarantined()
                          "
                          (click)="suspendPkg(pkg)"
                        >
                          Suspend
                        </button>
                      </div>
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="6" class="mc-empty-records">No matching records</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>

        <section class="mc-card mc-ext-card" aria-label="Service-Backed Read-Only Projection Explorer">
          <div class="mc-ext-section-head">
            <span>Service-Backed Read-Only Projection Query Explorer (Zero Raw DB Credentials)</span>
            <span class="mc-code">EXT-007</span>
          </div>
          <div class="mc-ext-body-stack">
            <div class="mc-ext-grid-4">
              <div>
                <label class="mc-field-label" for="mc-odbc-proj">Authorized Projection</label>
                <select
                  id="mc-odbc-proj"
                  class="mc-select mc-code"
                  data-testid="ext-odbc-projection"
                  [(ngModel)]="odbcProjection"
                >
                  <option value="TRIAL_BALANCE">TRIAL_BALANCE</option>
                  <option value="LEDGERS">LEDGERS</option>
                  <option value="VOUCHER_REGISTER">VOUCHER_REGISTER</option>
                  <option value="STOCK_SUMMARY">STOCK_SUMMARY</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-odbc-limit">Row Limit</label>
                <input
                  id="mc-odbc-limit"
                  type="number"
                  min="1"
                  max="500"
                  class="mc-input mc-code"
                  [(ngModel)]="odbcLimit"
                />
              </div>
              <div class="mc-ext-check-align">
                <label class="mc-ext-check">
                  <input type="checkbox" [(ngModel)]="odbcIncludeOptional" />
                  <span>Include Optional Vouchers</span>
                </label>
              </div>
              <div class="mc-ext-check-align">
                <button
                  type="button"
                  class="mc-btn mc-btn-primary"
                  data-testid="ext-odbc-run-btn"
                  [disabled]="busy()"
                  (click)="runOdbcProjection()"
                >
                  <mc-icon name="search" [size]="16" />
                  <span>Execute Read-Only Query</span>
                </button>
              </div>
            </div>

            @if (odbcResult(); as odbc) {
              <div class="mc-ext-table-wrap" data-testid="ext-odbc-result">
                <table class="mc-ext-table">
                  <thead>
                    <tr>
                      @for (col of odbc.columns; track col.name) {
                        <th scope="col">{{ col.name }}</th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of odbc.rows; track $index) {
                      <tr>
                        @for (col of odbc.columns; track col.name) {
                          <td class="mc-code">{{ row[col.name] ?? '' }}</td>
                        }
                      </tr>
                    } @empty {
                      <tr>
                        <td [attr.colspan]="odbc.columns.length || 1" class="mc-empty-records">
                          No matching records
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </section>
      }
    </div>
  `,
  styles: `
    .mc-ext-shell {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-ext-header {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
    }

    .mc-ext-heading-row,
    .mc-ext-inline-row {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-ext-title {
      margin: 0;
      font-size: var(--mc-font-size-lg);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-ext-subtitle {
      margin: 2px 0 0;
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
      color: var(--mc-color-slate-600);
    }

    .mc-ext-tabs {
      display: flex;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-ext-alert {
      padding: var(--mc-space-2) var(--mc-space-3);
      border-radius: var(--mc-radius-sm);
      font-size: var(--mc-font-size-sm);
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-ext-alert-error {
      background: var(--mc-color-danger-50);
      color: var(--mc-color-danger-700);
      border: 1px solid #FCA5A5;
    }

    .mc-ext-alert-ok {
      background: var(--mc-color-teal-50);
      color: var(--mc-color-pressed-teal);
      border: 1px solid var(--mc-color-teal-100);
    }

    .mc-ext-card {
      overflow: hidden;
    }

    .mc-ext-section-head {
      padding: 0.5rem 0.85rem;
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
    }

    .mc-ext-body-stack {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-ext-split-grid,
    .mc-ext-grid-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-ext-grid-4 {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    @media (max-width: 960px) {
      .mc-ext-split-grid,
      .mc-ext-grid-2,
      .mc-ext-grid-4 {
        grid-template-columns: 1fr;
      }
    }

    .mc-ext-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      flex-wrap: wrap;
      padding-top: var(--mc-space-2);
      border-top: var(--mc-border-subtle);
    }

    .mc-ext-meta-bar {
      padding: var(--mc-space-2) var(--mc-space-4);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-4);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-ext-check {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-ext-check-align {
      display: flex;
      align-items: flex-end;
    }

    .mc-ext-preview-box {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-xs);
    }

    .mc-ext-table-wrap {
      overflow-x: auto;
    }

    .mc-ext-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--mc-font-size-sm);

      th {
        padding: 0.45rem 0.75rem;
        background: var(--mc-color-slate-100);
        color: var(--mc-color-slate-700);
        font-size: var(--mc-font-size-xs);
        font-weight: 700;
        text-transform: uppercase;
        text-align: left;
        border-bottom: var(--mc-border-subtle);
      }

      td {
        padding: 0.45rem 0.75rem;
        border-bottom: 1px solid var(--mc-color-slate-100);
        font-weight: 500;
      }
    }

    .mc-badge-danger {
      background: var(--mc-color-danger-50);
      color: var(--mc-color-danger-700);
      border: 1px solid #FCA5A5;
    }

    .mc-badge-warn {
      background: #FFFBEB;
      color: var(--mc-color-warning-700, #6F4E00);
      border: 1px solid #FCD34D;
    }
  `,
})
export class ExtensionWorkbenchComponent implements OnInit {
  readonly companyContext = inject(CompanyContextStore);
  private readonly extensionService = inject(ExtensionService);

  readonly embeddedOverlay = input<boolean>(false);

  readonly activeTab = signal<ExtensionWorkbenchTab>('CUSTOM_FIELDS');
  readonly busy = signal<boolean>(false);
  readonly errorBanner = signal<string | null>(null);
  readonly statusBanner = signal<string | null>(null);

  // Tab 1: Custom Fields State
  readonly customFields = signal<readonly CustomFieldDefDto[]>([]);
  readonly entityFieldValues = signal<readonly CustomFieldValueDto[]>([]);
  cfNamespace = 'com.acme.logistics';
  cfEntityType: CustomFieldEntityType = 'VOUCHER';
  cfFieldCode = '';
  cfLabel = '';
  cfDataType: CustomFieldDataType = 'TEXT';
  cfProtectionClass: CustomFieldProtectionClass = 'STANDARD';
  cfUiAnchor = 'voucher.header.extra';
  cfDecimalScale = 2;
  cfEnumCsv = '';
  cfRequiredFlag = false;

  valEntityType: CustomFieldEntityType = 'VOUCHER';
  valEntityId = '';
  valFieldCode = '';
  valFieldValue = '';
  valStrictPrivate = false;

  // Tab 2: Bounded FEEL / DMN & Draft Package State
  previewExpression = 'amount <= 500000 and gstRegistered = true';
  dmnHitPolicy: DmnHitPolicy = 'FIRST';
  previewInputsJson = '{"amount":"125000.00","gstRegistered":true}';
  readonly previewResult = signal<EvaluatePreviewResponse | null>(null);

  pkgNamespace = 'com.acme.logistics';
  pkgDisplayName = 'Logistics Compliance Extension';
  pkgRuleCode = 'VCH_EWAYBILL_CHECK';
  pkgRuleErrorMessage = 'Vehicle registration number is required above threshold.';

  // Tab 3: Static TDL Importer State
  tdlProjectName = 'Legacy Logistics Customization';
  tdlFileName = 'logistics.tdl';
  tdlVersionClaim = 'Release 6.x';
  tdlSourceText =
    '[System: UDF]\nVehicleRegNo : String : 1001\n\n[Field: VchVehicleField]\nUse : Name Field\nStorage : VehicleRegNo';
  readonly activeTdlProject = signal<TdlImportProjectDto | null>(null);
  readonly tdlProjects = signal<readonly TdlImportProjectDto[]>([]);
  readonly tdlDispositionFilter = signal<string>('ALL');

  readonly filteredParityRows = computed(() => {
    const proj = this.activeTdlProject();
    if (!proj) {
      return [];
    }
    const filter = this.tdlDispositionFilter();
    if (filter === 'ALL') {
      return proj.parityMatrix;
    }
    return proj.parityMatrix.filter((r) => r.disposition === filter);
  });

  // Tab 4: Lifecycle & Read-Only Projection State
  readonly packages = signal<readonly ExtensionPackageDto[]>([]);
  readonly bindings = signal<readonly ExtensionBindingDto[]>([]);
  odbcProjection: OdbcProjectionName = 'TRIAL_BALANCE';
  odbcLimit = 50;
  odbcIncludeOptional = false;
  readonly odbcResult = signal<OdbcProjectionQueryResponse | null>(null);

  ngOnInit(): void {
    void this.loadActiveTabData();
  }

  switchTab(tab: ExtensionWorkbenchTab): void {
    this.activeTab.set(tab);
    this.errorBanner.set(null);
    this.statusBanner.set(null);
    void this.loadActiveTabData();
  }

  async loadActiveTabData(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    try {
      if (this.activeTab() === 'CUSTOM_FIELDS') {
        const fields = await this.extensionService.listCustomFields(cid);
        this.customFields.set(fields);
      } else if (this.activeTab() === 'TDL_IMPORTER') {
        const projects = await this.extensionService.listTdlImports(cid);
        this.tdlProjects.set(projects);
        if (!this.activeTdlProject() && projects.length > 0) {
          this.activeTdlProject.set(projects[0]);
        }
      } else if (this.activeTab() === 'LIFECYCLE_ODBC') {
        const [pkgs, bnds] = await Promise.all([
          this.extensionService.listPackages(cid),
          this.extensionService.listBindings(cid),
        ]);
        this.packages.set(pkgs);
        this.bindings.set(bnds);
      }
    } catch {
      // Non-fatal initial load
    }
  }

  async reloadCustomFields(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    try {
      const fields = await this.extensionService.listCustomFields(cid);
      this.customFields.set(fields);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load custom fields');
    }
  }

  async createFieldDefinition(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid || !this.cfNamespace.trim() || !this.cfFieldCode.trim() || !this.cfLabel.trim()) {
      return;
    }
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const enumOptions =
        this.cfDataType === 'ENUM'
          ? this.cfEnumCsv
              .split(',')
              .map((s) => s.trim())
              .filter((s) => s.length > 0)
          : null;
      const created = await this.extensionService.createCustomField(cid, {
        namespace: this.cfNamespace.trim(),
        ownerEntityType: this.cfEntityType,
        fieldCode: this.cfFieldCode.trim(),
        label: this.cfLabel.trim(),
        dataType: this.cfDataType,
        decimalScale: this.cfDataType === 'EXACT_DECIMAL' ? this.cfDecimalScale : null,
        enumOptions,
        protectionClass: this.cfProtectionClass,
        uiAnchor: this.cfUiAnchor,
        requiredFlag: this.cfRequiredFlag,
      });
      this.customFields.update((prev) => [...prev.filter((f) => f.id !== created.id), created]);
      this.cfFieldCode = '';
      this.cfLabel = '';
      this.statusBanner.set(
        `Saved custom field "${created.namespace}::${created.fieldCode}" (${created.protectionClass}).`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to create custom field');
    } finally {
      this.busy.set(false);
    }
  }

  async loadEntityCustomValues(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const eid = this.valEntityId.trim();
    if (!cid || !eid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const vals = await this.extensionService.readCustomFieldValues(
        cid,
        this.valEntityType,
        eid,
        this.valStrictPrivate,
      );
      this.entityFieldValues.set(vals);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to read entity values');
    } finally {
      this.busy.set(false);
    }
  }

  async saveEntityCustomValue(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    const eid = this.valEntityId.trim();
    const code = this.valFieldCode.trim();
    if (!cid || !eid || !code) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const vals = await this.extensionService.writeCustomFieldValues(
        cid,
        this.valEntityType,
        eid,
        {
          items: [
            {
              namespace: this.cfNamespace.trim(),
              fieldCode: code,
              value: this.valFieldValue,
            },
          ],
          values: { [`${this.cfNamespace.trim()}::${code}`]: this.valFieldValue },
        },
      );
      this.entityFieldValues.set(vals);
      this.statusBanner.set(`Updated custom field value "${code}".`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to write entity value');
    } finally {
      this.busy.set(false);
    }
  }

  async runFeelPreview(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    let parsedInputs: Record<string, unknown>;
    try {
      parsedInputs = JSON.parse(this.previewInputsJson) as Record<string, unknown>;
    } catch {
      this.errorBanner.set('Sample inputs must be valid JSON.');
      return;
    }

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const res = await this.extensionService.evaluatePreview(cid, {
        expression: this.previewExpression.trim(),
        inputs: parsedInputs,
      });
      this.previewResult.set(res);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to evaluate FEEL rule');
    } finally {
      this.busy.set(false);
    }
  }

  async createDraftExtensionPackage(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid || !this.pkgNamespace.trim() || !this.pkgDisplayName.trim()) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const created = await this.extensionService.createDraftPackage(cid, {
        namespace: this.pkgNamespace.trim(),
        displayName: this.pkgDisplayName.trim(),
        schemaDialect: JSON_SCHEMA_2020_12,
        targetProfiles: ['BROWSER', 'DESKTOP_SWING'],
        feelRules: [
          {
            ruleCode: this.pkgRuleCode.trim() || 'CUSTOM_RULE_1',
            label: this.pkgDisplayName.trim(),
            targetEntityType: 'VOUCHER',
            severity: 'ERROR',
            conditionExpression: this.previewExpression.trim(),
            errorMessage: this.pkgRuleErrorMessage.trim(),
            orderNo: 1,
          },
        ],
        decisionTables: [
          {
            tableCode: 'APPROVAL_ROUTING_DMN',
            label: 'Threshold Decision Table',
            targetEntityType: 'VOUCHER',
            hitPolicy: this.dmnHitPolicy,
            inputExpressions: ['amount'],
            rules: [
              {
                ruleId: 'R1',
                inputEntries: ['> 500000'],
                outcome: 'REQUIRE_APPROVAL',
                message: 'High-value voucher requires checker sign-off',
              },
            ],
          },
        ],
      });
      this.packages.update((prev) => [created, ...prev.filter((p) => p.id !== created.id)]);
      this.statusBanner.set(
        `Created inactive DRAFT extension package "${created.displayName}" (r${created.revisionNo}).`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(
        err instanceof Error ? err.message : 'Unable to create extension package',
      );
    } finally {
      this.busy.set(false);
    }
  }

  onTdlFileSelected(event: Event): void {
    const inputEl = event.target as HTMLInputElement | null;
    const file = inputEl?.files?.[0];
    if (!file) return;
    this.tdlFileName = file.name;
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string') {
        this.tdlSourceText = reader.result;
      }
    };
    reader.readAsText(file);
  }

  async analyzeTdlProject(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid || !this.tdlSourceText.trim()) return;

    this.busy.set(true);
    this.errorBanner.set(null);
    this.statusBanner.set(null);
    try {
      const imported = await this.extensionService.importTdlProject(cid, {
        projectName: this.tdlProjectName.trim() || 'Imported TDL Project',
        fileName: this.tdlFileName.trim() || 'project.tdl',
        sourceVersionClaim: this.tdlVersionClaim.trim() || null,
        tdlText: this.tdlSourceText,
      });
      this.activeTdlProject.set(imported);
      this.tdlProjects.update((prev) => [imported, ...prev.filter((p) => p.id !== imported.id)]);
      this.statusBanner.set(
        `Analyzed ${imported.fileName}: ${imported.parityMatrix.length} construct(s) classified into Functional Parity Matrix.`,
      );
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to analyze .tdl source');
    } finally {
      this.busy.set(false);
    }
  }

  async validatePkg(pkg: ExtensionPackageDto): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const validated = await this.extensionService.validatePackage(cid, pkg.id);
      this.packages.update((prev) => prev.map((p) => (p.id === validated.id ? validated : p)));
      this.statusBanner.set(`Package "${validated.displayName}" status: ${validated.status}.`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Validation failed');
    } finally {
      this.busy.set(false);
    }
  }

  async activatePkg(pkg: ExtensionPackageDto): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const binding = await this.extensionService.activatePackage(cid, pkg.id, pkg.versionNo);
      this.packages.update((prev) =>
        prev.map((p) => (p.id === pkg.id ? { ...p, bindingState: binding.state } : p)),
      );
      this.statusBanner.set(`Activated "${pkg.displayName}" for active company.`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Activation failed');
    } finally {
      this.busy.set(false);
    }
  }

  async suspendPkg(pkg: ExtensionPackageDto): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const binding = await this.extensionService.suspendPackage(cid, pkg.id);
      this.packages.update((prev) =>
        prev.map((p) => (p.id === pkg.id ? { ...p, bindingState: binding.state } : p)),
      );
      this.statusBanner.set(`Suspended "${pkg.displayName}" cleanly without losing stored values.`);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Suspension failed');
    } finally {
      this.busy.set(false);
    }
  }

  async runOdbcProjection(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.busy.set(true);
    this.errorBanner.set(null);
    try {
      const res = await this.extensionService.executeOdbcProjectionQuery(cid, {
        projection: this.odbcProjection,
        fromDate: this.companyContext.periodFrom(),
        toDate: this.companyContext.periodTo(),
        includeOptional: this.odbcIncludeOptional,
        limit: this.odbcLimit,
      });
      this.odbcResult.set(res);
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Projection query failed');
    } finally {
      this.busy.set(false);
    }
  }
}
