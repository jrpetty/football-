// Vite `?raw` imports: the file's text as a string (mock mode loads the real ladder tests this way, on demand).
declare module '*?raw' {
  const text: string;
  export default text;
}
