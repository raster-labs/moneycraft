import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../http/runtime-config.service';
import { AuthenticatedPrincipal } from './auth.store';

function base64UrlToUint8Array(base64Url: string): Uint8Array<ArrayBuffer> {
  const padded = base64Url
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(Math.ceil(base64Url.length / 4) * 4, '=');
  const binary = atob(padded);
  const buffer = new ArrayBuffer(binary.length);
  const bytes = new Uint8Array(buffer);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function arrayBufferToBase64Url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export interface ServerPublicKeyCreationOptions {
  readonly challenge: string;
  readonly rp: { readonly name: string; readonly id?: string };
  readonly user: { readonly id: string; readonly name: string; readonly displayName: string };
  readonly pubKeyCredParams: PublicKeyCredentialParameters[];
  readonly timeout?: number;
  readonly attestation?: AttestationConveyancePreference;
  readonly excludeCredentials?: readonly { readonly id: string; readonly type: 'public-key' }[];
}

export interface ServerPublicKeyRequestOptions {
  readonly challenge: string;
  readonly rpId?: string;
  readonly timeout?: number;
  readonly userVerification?: UserVerificationRequirement;
  readonly allowCredentials?: readonly { readonly id: string; readonly type: 'public-key' }[];
}

@Injectable({ providedIn: 'root' })
export class PasskeyService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);

  isSupported(): boolean {
    return (
      typeof window !== 'undefined' &&
      typeof navigator !== 'undefined' &&
      'credentials' in navigator &&
      typeof window.PublicKeyCredential !== 'undefined'
    );
  }

  async registerPasskey(label: string): Promise<void> {
    if (!this.isSupported()) {
      throw new Error('WebAuthn Passkeys are not supported in this browser context.');
    }

    const options = await firstValueFrom(
      this.http.post<ServerPublicKeyCreationOptions>(
        this.runtimeConfig.url('/auth/passkeys/register/options'),
        { label },
        { withCredentials: true },
      ),
    );

    const publicKey: PublicKeyCredentialCreationOptions = {
      challenge: base64UrlToUint8Array(options.challenge),
      rp: options.rp,
      user: {
        id: base64UrlToUint8Array(options.user.id),
        name: options.user.name,
        displayName: options.user.displayName,
      },
      pubKeyCredParams: options.pubKeyCredParams,
      timeout: options.timeout ?? 60_000,
      attestation: options.attestation ?? 'none',
      excludeCredentials: (options.excludeCredentials ?? []).map((cred) => ({
        id: base64UrlToUint8Array(cred.id),
        type: 'public-key',
      })),
    };

    const credential = (await navigator.credentials.create({
      publicKey,
    })) as PublicKeyCredential | null;

    if (!credential) {
      throw new Error('Passkey enrollment was cancelled or did not return a credential.');
    }

    const attestationResponse = credential.response as AuthenticatorAttestationResponse;

    await firstValueFrom(
      this.http.post<void>(
        this.runtimeConfig.url('/auth/passkeys/register/verify'),
        {
          label,
          credentialId: credential.id,
          rawId: arrayBufferToBase64Url(credential.rawId),
          type: credential.type,
          clientDataJSON: arrayBufferToBase64Url(attestationResponse.clientDataJSON),
          attestationObject: arrayBufferToBase64Url(attestationResponse.attestationObject),
        },
        { withCredentials: true },
      ),
    );
  }

  async authenticateWithPasskey(usernameHint?: string): Promise<AuthenticatedPrincipal> {
    if (!this.isSupported()) {
      throw new Error('WebAuthn Passkeys are not supported in this browser context.');
    }

    const options = await firstValueFrom(
      this.http.post<ServerPublicKeyRequestOptions>(
        this.runtimeConfig.url('/auth/passkeys/authenticate/options'),
        { username: usernameHint ?? null },
        { withCredentials: true },
      ),
    );

    const publicKey: PublicKeyCredentialRequestOptions = {
      challenge: base64UrlToUint8Array(options.challenge),
      rpId: options.rpId,
      timeout: options.timeout ?? 60_000,
      userVerification: options.userVerification ?? 'preferred',
      allowCredentials: (options.allowCredentials ?? []).map((cred) => ({
        id: base64UrlToUint8Array(cred.id),
        type: 'public-key',
      })),
    };

    const assertion = (await navigator.credentials.get({
      publicKey,
    })) as PublicKeyCredential | null;

    if (!assertion) {
      throw new Error('Passkey authentication was cancelled.');
    }

    const assertionResponse = assertion.response as AuthenticatorAssertionResponse;

    return firstValueFrom(
      this.http.post<AuthenticatedPrincipal>(
        this.runtimeConfig.url('/auth/passkeys/authenticate/verify'),
        {
          credentialId: assertion.id,
          rawId: arrayBufferToBase64Url(assertion.rawId),
          type: assertion.type,
          clientDataJSON: arrayBufferToBase64Url(assertionResponse.clientDataJSON),
          authenticatorData: arrayBufferToBase64Url(assertionResponse.authenticatorData),
          signature: arrayBufferToBase64Url(assertionResponse.signature),
          userHandle: assertionResponse.userHandle
            ? arrayBufferToBase64Url(assertionResponse.userHandle)
            : null,
        },
        { withCredentials: true },
      ),
    );
  }
}
