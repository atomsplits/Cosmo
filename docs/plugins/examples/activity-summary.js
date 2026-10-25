// Activity summary: totals of your recent XLM payments in and out.

cosmo.registerWidget({
  id: 'summary',
  title: 'Activity summary',
  render: function (data) {
    var received = 0, sent = 0;
    data.payments.forEach(function (p) {
      if (p.asset !== 'XLM') return;
      if (p.incoming) received += p.value; else sent += p.value;
    });
    return ui.column(
      ui.title('Last ' + data.payments.length + ' payments'),
      ui.text('Received: ' + received.toFixed(2) + ' XLM', { color: '#2E7D32' }),
      ui.text('Sent: ' + sent.toFixed(2) + ' XLM', { color: '#C62828' }),
      ui.divider(),
      ui.text('Net: ' + (received - sent).toFixed(2) + ' XLM', { style: 'label' })
    );
  }
});
