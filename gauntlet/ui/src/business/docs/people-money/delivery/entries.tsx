import type { DocEntry } from '../types.ts';
import { deliveryQuestion } from '../model/delivery.ts';
import { DeliveryBeats, DeliveryInline } from './Delivery.tsx';

export const DELIVERY_NOTE: DocEntry = {
  family: 'extraction',
  caption: 'The purchase order, the supplier’s delivery note and our goods-in check side by side, one row per ordered product, with the model’s count (received, damaged, short, over, substitute) against the correct one.',
  build: (input) => (deliveryQuestion(input.turns[0] ?? '') ? input : null),
  render: (_m, mode, input) => <DeliveryInline input={input} mode={mode} />,
  Beats: DeliveryBeats,
};
