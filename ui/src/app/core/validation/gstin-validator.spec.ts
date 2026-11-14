import { describe, expect, it } from 'vitest';
import { computeGstinMod36CheckChar, validateGstin } from './gstin-validator';

describe('GSTIN Mod-36 Validator', () => {
  it('validates all live TallyPrime 7.2 Parity Benchmark GSTINs', () => {
    const benchmarkGstins = [
      { gstin: '33AABCM1234F1Z4', state: '33', pan: 'AABCM1234F' },
      { gstin: '33AAFCK5678G1ZC', state: '33', pan: 'AAFCK5678G' },
      { gstin: '33AABCS9012H1ZM', state: '33', pan: 'AABCS9012H' },
      { gstin: '29AADCA3456J1ZI', state: '29', pan: 'AADCA3456J' },
    ];

    for (const item of benchmarkGstins) {
      const res = validateGstin(item.gstin, item.state);
      expect(res.valid).toBe(true);
      expect(res.pan).toBe(item.pan);
      expect(res.stateCode).toBe(item.state);
    }
  });

  it('detects Mod-36 checksum corruption and state code mismatches', () => {
    expect(computeGstinMod36CheckChar('33AABCM1234F1Z')).toBe('4');

    const badChecksum = validateGstin('33AABCM1234F1Z9');
    expect(badChecksum.valid).toBe(false);
    expect(badChecksum.expectedCheckChar).toBe('4');
    expect(badChecksum.error).toContain('Mod-36 checksum mismatch');

    const wrongState = validateGstin('29AADCA3456J1ZI', '33');
    expect(wrongState.valid).toBe(false);
    expect(wrongState.error).toContain('does not match selected state');

    const invalidState = validateGstin('98AABCM1234F1Z4');
    expect(invalidState.valid).toBe(false);
    expect(invalidState.error).toContain('Invalid GST state code');
  });
});
