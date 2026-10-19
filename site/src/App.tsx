import { lazy } from "react";
import { Link, Route, Routes } from "react-router";
import { Layout } from "./components/Layout";
import { Home } from "./pages/Home";

const Play = lazy(() => import("./pages/Play").then((m) => ({ default: m.Play })));
const HowItWorks = lazy(() => import("./pages/HowItWorks").then((m) => ({ default: m.HowItWorks })));
const Guide = lazy(() => import("./pages/Guide").then((m) => ({ default: m.Guide })));
const Catalog = lazy(() => import("./pages/Catalog").then((m) => ({ default: m.Catalog })));
const Quiz = lazy(() => import("./pages/Quiz").then((m) => ({ default: m.Quiz })));
const Commands = lazy(() => import("./pages/Commands").then((m) => ({ default: m.Commands })));

function NotFound() {
  return (
    <div className="page">
      <p className="kicker">404</p>
      <h1>No page here</h1>
      <p>
        <Link to="/">Back to the start</Link>
      </p>
    </div>
  );
}

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="play" element={<Play />} />
        <Route path="how-it-works" element={<HowItWorks />} />
        <Route path="guide" element={<Guide />} />
        <Route path="guide/:slug" element={<Guide />} />
        <Route path="catalog" element={<Catalog />} />
        <Route path="quiz" element={<Quiz />} />
        <Route path="commands" element={<Commands />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
