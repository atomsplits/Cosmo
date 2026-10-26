// Tip jar: one-tap buttons that open a pre-filled payment. Nothing is sent until you review and
// confirm it in the payment form. Change TIP_ADDRESS to the wallet you want to tip.

var TIP_ADDRESS = 'GAAZI4TCR3TY5OJHCTJC2A4QSY6CJWJH5IAJTGKIN2ER7LBNVKOCCWN7';

function tip(amount) {
  return ui.button(amount + ' XLM', function () {
    cosmo.suggestPayment({ to: TIP_ADDRESS, amount: amount, memo: 'Tip' });
  });
}

cosmo.registerWidget({
  id: 'tips',
  title: 'Tip jar',
  render: function (data) {
    if (!data.activated) return ui.text('Activate your wallet to send tips.');
    return ui.column(ui.title('Send a tip'), ui.row(tip(1), tip(5), tip(10)));
  }
});
