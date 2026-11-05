/** Entry of the dev-only review page ui/rung-thumbs.html: the app's styles and the thumbnail showcase, nothing else. */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../../../styles/tokens.css';
import '../../../styles/base.css';
import '../../../styles/system.css';
import RungThumbsShowcase from './Showcase.tsx';

const root = document.getElementById('root');
if (root) {
  createRoot(root).render(
    <StrictMode>
      <RungThumbsShowcase />
    </StrictMode>,
  );
}
