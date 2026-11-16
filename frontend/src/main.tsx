import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { createDataSource } from "./data";
import "./styles.css";

createDataSource().then((source) => {
  createRoot(document.getElementById("root")!).render(<StrictMode><App source={source} /></StrictMode>);
});
