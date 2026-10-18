import { Component, type ReactNode } from "react";

/** A page chunk that fails to load (the site was rebuilt while the tab was open) shows a reload button, not a blank page. */
export class PageBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="page loading" role="alert">
        <p>This page did not load. The site was probably rebuilt since you opened it.</p>
        <button type="button" className="btn" onClick={() => window.location.reload()}>
          Reload the page
        </button>
      </div>
    );
  }
}
