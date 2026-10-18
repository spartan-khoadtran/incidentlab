import { Link, Route, Routes } from "react-router";
import { Layout } from "./components/Layout";
import { Catalog } from "./pages/Catalog";
import { Commands } from "./pages/Commands";
import { Guide } from "./pages/Guide";
import { Home } from "./pages/Home";
import { HowItWorks } from "./pages/HowItWorks";
import { Play } from "./pages/Play";
import { Quiz } from "./pages/Quiz";
import { Repo } from "./pages/Repo";

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
        <Route path="repo" element={<Repo />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
