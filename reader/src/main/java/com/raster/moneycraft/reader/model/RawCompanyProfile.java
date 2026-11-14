package com.raster.moneycraft.reader.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Extracted company profile from Company.1800 or companion 01_company_profile.xml.
 */
public record RawCompanyProfile(
        String companyNumber,
        String guid,
        String name,
        String formalName,
        String stateName,
        String countryName,
        String pincode,
        String email,
        LocalDate startingFrom,
        LocalDate booksFrom,
        LocalDate auditedUpTo,
        String baseCurrencySymbol,
        String baseCurrencyFormalName
) {
    public RawCompanyProfile {
        Objects.requireNonNull(companyNumber, "companyNumber must not be null");
        Objects.requireNonNull(guid, "guid must not be null");
        Objects.requireNonNull(name, "name must not be null");
        formalName = formalName == null ? "" : formalName;
        stateName = stateName == null ? "" : stateName;
        countryName = countryName == null ? "" : countryName;
        pincode = pincode == null ? "" : pincode;
        email = email == null ? "" : email;
        baseCurrencySymbol = baseCurrencySymbol == null ? "₹" : baseCurrencySymbol;
        baseCurrencyFormalName = baseCurrencyFormalName == null ? "INR" : baseCurrencyFormalName;
    }
}
