// Copyright 2022 NyarTech LLC. All rights reserved.
// Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.

part of hyperpay;

/// The interface for Hyperpay SDK.
/// To use this plugin, you will need to have 2 endpoints on your server.
///
/// Please check
/// [the guide to setup your server](https://wordpresshyperpay.docs.oppwa.com/tutorials/mobile-sdk/integration/server).
///
/// Refer to [HyperPay API](https://wordpresshyperpay.docs.oppwa.com/reference/parameters)
/// for more information on Test/Live systems.
class HyperpayPlugin {
  HyperpayPlugin._(this._config);
  //static HyperpayPlugin instance = HyperpayPlugin._();

  static const MethodChannel _channel =
      const MethodChannel('plugins.nyartech.com/hyperpay');

  late HyperpayConfig _config;

  /// Read the configurations used to setup this instance of HyperPay.
  HyperpayConfig get config => _config;

  /// Setup HyperPay instance with the required stuff to make a successful
  /// payment transaction.
  ///
  /// See [HyperpayConfig], [PaymentMode]
  static Future<HyperpayPlugin> setup({required HyperpayConfig config}) async {
    await _channel.invokeMethod(
      'setup_service',
      {
        'mode': config.paymentMode.string,
      },
    );

    return HyperpayPlugin._(config);
  }

  /// Perform the transaction using iOS/Android HyperPay SDK.
  ///
  /// It's highly recommended to setup a listner using
  /// [HyperPay webhooks](https://wordpresshyperpay.docs.oppwa.com/tutorials/webhooks),
  /// and perform the requird action after payment (e.g. issue receipt) on your server.
  Future<PaymentStatus> pay(
    String checkoutId,
    BrandType brand,
    CardInfo card,
  ) async {
    try {
      final result = await _channel.invokeMethod(
        'start_payment_transaction',
        {
          'checkoutID': checkoutId,
          'brand': brand.name.toUpperCase(),
          'card': card.toMap(),
        },
      );

      debugPrint('HyperpayPlugin/platformResponse: $result');

      if (result == 'synchronous' || result == 'success') {
        return PaymentStatus.successful;
      } else if (result == 'canceled') {
        return PaymentStatus.init;
      } else {
        return PaymentStatus.rejected;
      }
    } catch (e) {
      debugPrint('HyperpayPlugin/platformResponse: $e');
      rethrow;
    }
  }

  /// Perform a transaction natively with Apple Pay.
  ///
  /// This method will throw a [NOT_SUPPORTED] error on any platform other than iOS.
  Future<PaymentStatus> payWithApplePay(
    String checkoutId,
    ApplePaySettings applePay,
  ) async {
    if (defaultTargetPlatform != TargetPlatform.iOS) {
      throw HyperpayException(
        'Apple Pay is not supported on $defaultTargetPlatform.',
        'NOT_SUPPORTED',
      );
    }

    try {
      final result = await _channel.invokeMethod(
        'start_payment_transaction',
        {
          'checkoutID': checkoutId,
          'brand': BrandType.applePay.name.toUpperCase(),
          ...applePay.toJson(),
        },
      );

      debugPrint('HyperpayPlugin/platformResponse: $result');

      if (result == 'synchronous' || result == 'success') {
        return PaymentStatus.successful;
      } else if (result == 'canceled') {
        return PaymentStatus.init;
      } else {
        return PaymentStatus.rejected;
      }
    } catch (e) {
      debugPrint('HyperpayPlugin/platformResponse: $e');
      rethrow;
    }
  }

  /// Perform a transaction natively with Apple Pay.
  ///
  /// This method will throw a [NOT_SUPPORTED] error on any platform other than iOS.
  Future<PaymentStatus> payWithStcPay(
    String checkoutId,
    String phoneNumber,
  ) async {
    try {
      final result = await _channel.invokeMethod(
        'start_payment_transaction',
        {
          'checkoutID': checkoutId,
          'brand': BrandType.stcPay.name.toUpperCase(),
          'phoneNumber': phoneNumber,
        },
      );

      debugPrint('HyperpayPlugin/platformResponse: $result');

      if (result == 'synchronous' || result == 'success') {
        return PaymentStatus.successful;
      } else if (result == 'canceled') {
        return PaymentStatus.init;
      } else {
        return PaymentStatus.rejected;
      }
    } catch (e) {
      debugPrint('HyperpayPlugin/platformResponse: $e');
      rethrow;
    }
  }
}
