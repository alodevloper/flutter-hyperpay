// ignore_for_file: public_member_api_docs, sort_constructors_first
// Copyright 2022 NyarTech LLC. All rights reserved.
// Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.

part of hyperpay;

/// Payments can happen either on **Test** or **Live** mode,
/// each mode has a different set of entity IDs, these are
/// provided by HyperPay along with your merchant account.
///
/// You have to implement this class as an interface to define
/// both environment with its properties.
///
/// ```dart
/// class TestConfig implements HyperpayConfig {
///   PaymentMode paymentMode = PaymentMode.test;
///   String? creditcardEntityID = 'PASTE CREDIT CARD ENTITY ID';
///   String? madaEntityID = 'PASTE MADA ENTITY ID';
///}
/// ```
/// config for future other parameters
class HyperpayConfig {
  PaymentMode paymentMode = PaymentMode.none;
  HyperpayConfig._({
    required this.paymentMode,
  });
  //test
  factory HyperpayConfig.test() => HyperpayConfig._(
        paymentMode: PaymentMode.test,
      );

  //live
  factory HyperpayConfig.live() => HyperpayConfig._(
        paymentMode: PaymentMode.live,
      );
}
