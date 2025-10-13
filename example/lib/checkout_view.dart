import 'dart:convert';
import 'dart:developer' as dev;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:hyperpay/hyperpay.dart';
import 'package:hyperpay_example/constants.dart';
import 'package:hyperpay_example/formatters.dart';

class CheckoutView extends StatefulWidget {
  const CheckoutView({
    Key? key,
  }) : super(key: key);

  @override
  _CheckoutViewState createState() => _CheckoutViewState();
}

class _CheckoutViewState extends State<CheckoutView> {
  AutovalidateMode autovalidateMode = AutovalidateMode.disabled;

  TextEditingController holderNameController = TextEditingController();
  TextEditingController cardNumberController = TextEditingController();
  TextEditingController expiryController = TextEditingController();
  TextEditingController cvvController = TextEditingController();

  BrandType brandType = BrandType.none;

  bool isLoading = false;
  String sessionCheckoutID = '';

  late HyperpayPlugin hyperpay;

  @override
  void initState() {
    setup();
    super.initState();
  }

  setup() async {
    hyperpay = await HyperpayPlugin.setup(config: TestConfig());
  }

  Future<String?> getCheckOut() async {
    final url =
        Uri.parse('https://dev.hyperpay.com/hyperpay-demo/getcheckoutid.php');
    final response = await http.get(url);
    if (response.statusCode == 200) {
      dev.log(json.decode(response.body)['id'].toString(), name: 'checkoutId');
      return json.decode(response.body)['id'];
    } else {
      dev.log(response.body.toString(), name: 'STATUS CODE ERROR');
      return null;
    }
  }

  Future<void> getpaymentstatus(String checkoutid) async {
    bool status;

    final Uri myUrl = Uri.parse(
      'https://dev.hyperpay.com/hyperpay-demo/getpaymentstatus.php?id=$checkoutid',
    );
    final response = await http.post(
      myUrl,
      headers: {'Accept': 'application/json'},
    );
    status = response.body.contains('error');

    final data = json.decode(response.body);

    print("payment_status: ${data["result"].toString()}");
  }

  Future<void> onPay(context) async {
    final bool valid = Form.of(context).validate() ?? false;

    if (valid) {
      setState(() {
        isLoading = true;
      });

      // Make a CardInfo from the controllers
      CardInfo card = CardInfo(
        holder: holderNameController.text,
        cardNumber: cardNumberController.text.replaceAll(' ', ''),
        cvv: cvvController.text,
        expiryMonth: expiryController.text.split('/')[0],
        expiryYear: '20${expiryController.text.split('/')[1]}',
      );

      try {
        final checkoutId = await getCheckOut();

        final result = await hyperpay.pay(checkoutId!, BrandType.master, card);

        switch (result) {
          case PaymentStatus.init:
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(
                content: Text('Payment session is still in progress'),
                backgroundColor: Colors.amber,
              ),
            );
            break;
          // For the sake of the example, the 2 cases are shown explicitly
          // but in real world it's better to merge pending with successful
          // and delegate the job from there to the server, using webhooks
          // to get notified about the final status and do some action.
          case PaymentStatus.pending:
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(
                content: Text('Payment pending ⏳'),
                backgroundColor: Colors.amber,
              ),
            );
            break;
          case PaymentStatus.successful:
            await getpaymentstatus(checkoutId);
            sessionCheckoutID = '';
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(
                content: Text('Payment approved 🎉'),
                backgroundColor: Colors.green,
              ),
            );
            break;

          default:
        }
      } on HyperpayException catch (exception) {
        sessionCheckoutID = '';
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(exception.details ?? exception.message),
            backgroundColor: Colors.red,
          ),
        );
      } catch (exception) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('$exception'),
          ),
        );
      }

      setState(() {
        isLoading = false;
      });
    } else {
      setState(() {
        autovalidateMode = AutovalidateMode.onUserInteraction;
      });
    }
  }

  void onApplePay(double amount) async {
    setState(() {
      isLoading = true;
    });

    final applePaySettings = ApplePaySettings(
      amount: amount,
      appleMerchantId: 'merchant.com.emaan.app',
      countryCode: 'SA',
      currencyCode: 'SAR',
      label: '',
    );

    try {
      final checkoutId = await getCheckOut();
      await hyperpay.payWithApplePay(checkoutId!, applePaySettings);
      await getpaymentstatus(checkoutId);
    } catch (exception) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('$exception'),
        ),
      );
    } finally {
      setState(() {
        isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Checkout"),
      ),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(8.0),
          child: Form(
            autovalidateMode: autovalidateMode,
            child: Builder(
              builder: (context) {
                return Column(
                  children: [
                    const SizedBox(height: 10),
                    // Holder
                    TextFormField(
                      controller: holderNameController,
                      decoration: _inputDecoration(
                        label: "Card Holder",
                        hint: "Jane Jones",
                        icon: Icons.account_circle_rounded,
                      ),
                    ),
                    const SizedBox(height: 10),
                    // Number
                    TextFormField(
                      controller: cardNumberController,
                      decoration: _inputDecoration(
                        label: "Card Number",
                        hint: "0000 0000 0000 0000",
                        icon: brandType == BrandType.none
                            ? Icons.credit_card
                            : 'assets/${brandType.name}.png',
                      ),
                      onChanged: (value) {
                        setState(() {
                          brandType = value.detectBrand;
                        });
                      },
                      inputFormatters: [
                        FilteringTextInputFormatter.digitsOnly,
                        LengthLimitingTextInputFormatter(brandType.maxLength),
                        CardNumberInputFormatter()
                      ],
                      validator: (String? number) =>
                          brandType.validateNumber(number ?? ""),
                    ),
                    const SizedBox(height: 10),
                    // Expiry date
                    TextFormField(
                      controller: expiryController,
                      decoration: _inputDecoration(
                        label: "Expiry Date",
                        hint: "MM/YY",
                        icon: Icons.date_range_rounded,
                      ),
                      inputFormatters: [
                        FilteringTextInputFormatter.digitsOnly,
                        LengthLimitingTextInputFormatter(4),
                        CardMonthInputFormatter(),
                      ],
                      validator: (String? date) =>
                          CardInfo.validateDate(date ?? ""),
                    ),
                    const SizedBox(height: 10),
                    // CVV
                    TextFormField(
                      controller: cvvController,
                      decoration: _inputDecoration(
                        label: "CVV",
                        hint: "000",
                        icon: Icons.confirmation_number_rounded,
                      ),
                      inputFormatters: [
                        FilteringTextInputFormatter.digitsOnly,
                        LengthLimitingTextInputFormatter(4),
                      ],
                      validator: (String? cvv) =>
                          CardInfo.validateCVV(cvv ?? ""),
                    ),
                    const SizedBox(height: 30),
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton(
                        onPressed: isLoading ? null : () => onPay(context),
                        child: Text(
                          isLoading
                              ? 'Processing your request, please wait...'
                              : 'PAY',
                        ),
                      ),
                    ),
                    if (defaultTargetPlatform == TargetPlatform.iOS)
                      SizedBox(
                        width: double.infinity,
                        height: 35,
                        child: ApplePayButton(
                          onPressed: () => onApplePay(1),
                          type: ApplePayButtonType.buy,
                          style: ApplePayButtonStyle.black,
                        ),
                      ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }

  InputDecoration _inputDecoration(
      {String? label, String? hint, dynamic icon}) {
    return InputDecoration(
      hintText: hint,
      labelText: label,
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(5.0)),
      prefixIcon: icon is IconData
          ? Icon(icon)
          : Container(
              padding: const EdgeInsets.all(6),
              width: 10,
              child: Image.asset(icon),
            ),
    );
  }
}
