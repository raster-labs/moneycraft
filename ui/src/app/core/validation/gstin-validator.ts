/**
 * MoneyCraft 1.0.0 — Indian GSTIN Structure & Luhn Mod-36 Checksum Validator
 *
 * Format: [0..1] State Code + [2..11] PAN + [12] Entity Code + [13] 'Z'/Default + [14] Mod-36 Checksum
 */

export interface IndianGstState {
  readonly code: string;
  readonly name: string;
}

export const INDIAN_GST_STATES: readonly IndianGstState[] = [
  { code: '01', name: 'Jammu and Kashmir' },
  { code: '02', name: 'Himachal Pradesh' },
  { code: '03', name: 'Punjab' },
  { code: '04', name: 'Chandigarh' },
  { code: '05', name: 'Uttarakhand' },
  { code: '06', name: 'Haryana' },
  { code: '07', name: 'Delhi' },
  { code: '08', name: 'Rajasthan' },
  { code: '09', name: 'Uttar Pradesh' },
  { code: '10', name: 'Bihar' },
  { code: '11', name: 'Sikkim' },
  { code: '12', name: 'Arunachal Pradesh' },
  { code: '13', name: 'Nagaland' },
  { code: '14', name: 'Manipur' },
  { code: '15', name: 'Mizoram' },
  { code: '16', name: 'Tripura' },
  { code: '17', name: 'Meghalaya' },
  { code: '18', name: 'Assam' },
  { code: '19', name: 'West Bengal' },
  { code: '20', name: 'Jharkhand' },
  { code: '21', name: 'Odisha' },
  { code: '22', name: 'Chhattisgarh' },
  { code: '23', name: 'Madhya Pradesh' },
  { code: '24', name: 'Gujarat' },
  { code: '26', name: 'Dadra and Nagar Haveli and Daman and Diu' },
  { code: '27', name: 'Maharashtra' },
  { code: '28', name: 'Andhra Pradesh (Old)' },
  { code: '29', name: 'Karnataka' },
  { code: '30', name: 'Goa' },
  { code: '31', name: 'Lakshadweep' },
  { code: '32', name: 'Kerala' },
  { code: '33', name: 'Tamil Nadu' },
  { code: '34', name: 'Puducherry' },
  { code: '35', name: 'Andaman and Nicobar Islands' },
  { code: '36', name: 'Telangana' },
  { code: '37', name: 'Andhra Pradesh' },
  { code: '38', name: 'Ladakh' },
  { code: '97', name: 'Other Territory' },
  { code: '99', name: 'Centre Jurisdiction' },
];

const VALID_STATE_CODES = new Set(INDIAN_GST_STATES.map((s) => s.code));
const MOD36_ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const GSTIN_STRUCTURE_REGEX = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;

export interface GstinValidationResult {
  readonly valid: boolean;
  readonly normalized: string;
  readonly stateCode?: string;
  readonly stateName?: string;
  readonly pan?: string;
  readonly expectedCheckChar?: string;
  readonly error?: string;
}

export function computeGstinMod36CheckChar(first14Chars: string): string {
  const upper = first14Chars.toUpperCase();
  if (upper.length !== 14) {
    throw new Error('GSTIN prefix must be exactly 14 characters');
  }

  let factor = 1;
  let sum = 0;

  for (let i = 0; i < 14; i += 1) {
    const codePoint = MOD36_ALPHABET.indexOf(upper[i]);
    if (codePoint < 0) {
      throw new Error(`Invalid character "${upper[i]}" in GSTIN`);
    }
    const digit = factor * codePoint;
    const quotient = Math.floor(digit / 36);
    const remainder = digit % 36;
    sum += quotient + remainder;
    factor = factor === 1 ? 2 : 1;
  }

  const checkCodePoint = (36 - (sum % 36)) % 36;
  return MOD36_ALPHABET[checkCodePoint];
}

export function validateGstin(
  rawGstin: string,
  expectedStateCode?: string,
): GstinValidationResult {
  const normalized = rawGstin.trim().toUpperCase();

  if (normalized.length !== 15) {
    return {
      valid: false,
      normalized,
      error: 'GSTIN must be exactly 15 alphanumeric characters.',
    };
  }

  const stateCode = normalized.slice(0, 2);
  if (!VALID_STATE_CODES.has(stateCode)) {
    return {
      valid: false,
      normalized,
      stateCode,
      error: `Invalid GST state code "${stateCode}".`,
    };
  }

  if (expectedStateCode && expectedStateCode !== stateCode) {
    return {
      valid: false,
      normalized,
      stateCode,
      error: `GSTIN state code "${stateCode}" does not match selected state "${expectedStateCode}".`,
    };
  }

  if (!GSTIN_STRUCTURE_REGEX.test(normalized)) {
    return {
      valid: false,
      normalized,
      stateCode,
      error: 'GSTIN structure must match NN + PAN(10) + Entity + Z + Checksum.',
    };
  }

  const expectedCheckChar = computeGstinMod36CheckChar(normalized.slice(0, 14));
  const actualCheckChar = normalized[14];
  if (actualCheckChar !== expectedCheckChar) {
    return {
      valid: false,
      normalized,
      stateCode,
      pan: normalized.slice(2, 12),
      expectedCheckChar,
      error: `GSTIN Mod-36 checksum mismatch (expected '${expectedCheckChar}', found '${actualCheckChar}').`,
    };
  }

  const stateInfo = INDIAN_GST_STATES.find((s) => s.code === stateCode);
  return {
    valid: true,
    normalized,
    stateCode,
    stateName: stateInfo?.name,
    pan: normalized.slice(2, 12),
    expectedCheckChar,
  };
}
