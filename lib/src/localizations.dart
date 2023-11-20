part of hyperpay;

abstract class _Messages {
  String reqired();

  String inavlidVisaNumber();

  String inavlidMastercardNumber();

  String inavlidAmericanExpressNumber();

  String inavlidMadaNumber();

  String noBrandProvided();

  String expiryMonthIsInvalid();

  String expiryYearIsInvalid();

  String cardHasExpired();

  String cVVIsInvalid();
}

class EnMessages extends _Messages {
  @override
  String cVVIsInvalid() {
    return 'CVV is invalid';
  }

  @override
  String cardHasExpired() {
    return 'Card has expired';
  }

  @override
  String expiryMonthIsInvalid() {
    return 'Expiry month is invalid';
  }

  @override
  String expiryYearIsInvalid() {
    return 'Expiry Year is invalid';
  }

  @override
  String inavlidMadaNumber() {
    return 'Invalid MADA number';
  }

  @override
  String inavlidMastercardNumber() {
    return 'Invalid MASTER CARD number';
  }

  @override
  String inavlidVisaNumber() {
    return 'Invalid VISA number';
  }

  @override
  String noBrandProvided() {
    return 'No brand provided';
  }

  @override
  String reqired() {
    return 'Reqired';
  }

  @override
  String inavlidAmericanExpressNumber() {
    return 'Invalid AmericanExpress number';
  }
}

class ArMessages extends _Messages {
  @override
  String cVVIsInvalid() {
    return 'CVV غير صحيح';
  }

  @override
  String cardHasExpired() {
    return 'انتهت صلاحية البطاقة';
  }

  @override
  String expiryMonthIsInvalid() {
    return 'الشهر غير صحيح';
  }

  @override
  String expiryYearIsInvalid() {
    return 'السنة غير صحيحة';
  }

  @override
  String inavlidMadaNumber() {
    return 'رقم Mada غير صحيح';
  }

  @override
  String inavlidMastercardNumber() {
    return 'رقم MASTER CARD غير صحيح';
  }

  @override
  String inavlidVisaNumber() {
    return 'رقم VISA غير صحيح';
  }

  @override
  String noBrandProvided() {
    return 'قيمة غير صحيحة';
  }

  @override
  String reqired() {
    return 'حقل مطلوب';
  }

  @override
  String inavlidAmericanExpressNumber() {
    return 'رقم AmericanExpress غير صحيح';
  }
}
