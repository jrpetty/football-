// Vite `?url` imports of bundled images (mock-mode vision fixtures).
declare module '*.png?url' {
  const url: string;
  export default url;
}
