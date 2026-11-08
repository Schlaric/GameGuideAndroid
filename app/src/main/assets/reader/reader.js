(function () {
  "use strict";
  if (!/(^|\.)ign\.com$/i.test(location.hostname) || !location.pathname.startsWith("/wikis/")) return;

  const selectors = [
    "[data-testid='article-body']",
    "[data-testid*='wiki-content']",
    ".wiki-content",
    ".article-content",
    ".article-body",
    "#article-content",
    "article"
  ];

  function findArticle() {
    for (const selector of selectors) {
      const candidates = document.querySelectorAll(selector);
      for (const candidate of candidates) {
        if (candidate.innerText && candidate.innerText.trim().length > 250) return candidate;
      }
    }
    return null;
  }

  function applyReaderMode() {
    const article = findArticle();
    if (!article) return false;

    document.documentElement.classList.add("gameguide-reader-mode");
    article.classList.add("gameguide-reader-content");

    if (!document.getElementById("gameguide-reader-style")) {
      const style = document.createElement("style");
      style.id = "gameguide-reader-style";
      style.textContent = `
        html.gameguide-reader-mode,
        html.gameguide-reader-mode body {
          background: #17191f !important;
          color: #e7e9ee !important;
          color-scheme: dark !important;
        }
        html.gameguide-reader-mode body {
          margin: 0 !important;
          padding: 12px 16px 48px !important;
          font-family: Arial, sans-serif !important;
        }
        html.gameguide-reader-mode header:not(.gameguide-reader-content),
        html.gameguide-reader-mode footer,
        html.gameguide-reader-mode nav,
        html.gameguide-reader-mode aside,
        html.gameguide-reader-mode [role="navigation"],
        html.gameguide-reader-mode [role="complementary"],
        html.gameguide-reader-mode [data-testid*="global-nav"],
        html.gameguide-reader-mode [data-testid*="site-header"],
        html.gameguide-reader-mode [data-testid*="footer"],
        html.gameguide-reader-mode [data-testid*="sidebar"],
        html.gameguide-reader-mode [data-testid*="advert"],
        html.gameguide-reader-mode [class*="ad-container"],
        html.gameguide-reader-mode [class*="AdContainer"],
        html.gameguide-reader-mode [class*="ad-slot"],
        html.gameguide-reader-mode [class*="advert"],
        html.gameguide-reader-mode [id*="advert"],
        html.gameguide-reader-mode [class*="outbrain"],
        html.gameguide-reader-mode [class*="taboola"],
        html.gameguide-reader-mode [class*="newsletter"],
        html.gameguide-reader-mode [class*="recommend"],
        html.gameguide-reader-mode [class*="related-content"],
        html.gameguide-reader-mode [class*="social-share"],
        html.gameguide-reader-mode [class*="sticky"],
        html.gameguide-reader-mode [class*="modal"],
        html.gameguide-reader-mode [class*="overlay"],
        html.gameguide-reader-mode #IGNHeader,
        html.gameguide-reader-mode #ign-header,
        html.gameguide-reader-mode #ignFooter,
        html.gameguide-reader-mode .ign-header,
        html.gameguide-reader-mode .ign-footer,
        html.gameguide-reader-mode .site-header,
        html.gameguide-reader-mode .global-header,
        html.gameguide-reader-mode .global-navigation,
        html.gameguide-reader-mode .article-sidebar,
        html.gameguide-reader-mode .wiki-sidebar {
          display: none !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content {
          display: block !important;
          visibility: visible !important;
          width: 100% !important;
          max-width: 820px !important;
          margin: 0 auto !important;
          padding: 0 !important;
          background: #17191f !important;
          color: #e7e9ee !important;
          font-family: Arial, sans-serif !important;
          font-size: 17px !important;
          line-height: 1.7 !important;
          box-shadow: none !important;
          border: 0 !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content * {
          box-sizing: border-box;
          color: inherit;
        }
        html.gameguide-reader-mode .gameguide-reader-content p,
        html.gameguide-reader-mode .gameguide-reader-content li {
          line-height: 1.7 !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content img,
        html.gameguide-reader-mode .gameguide-reader-content picture,
        html.gameguide-reader-mode .gameguide-reader-content video,
        html.gameguide-reader-mode .gameguide-reader-content iframe {
          height: auto !important;
          max-width: 100% !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content a {
          color: #83b7ff !important;
          text-decoration: underline !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content h1,
        html.gameguide-reader-mode .gameguide-reader-content h2,
        html.gameguide-reader-mode .gameguide-reader-content h3,
        html.gameguide-reader-mode .gameguide-reader-content h4 {
          color: #ffffff !important;
          line-height: 1.3 !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content figure,
        html.gameguide-reader-mode .gameguide-reader-content blockquote,
        html.gameguide-reader-mode .gameguide-reader-content pre,
        html.gameguide-reader-mode .gameguide-reader-content table {
          background: #222630 !important;
          border-color: #3a404d !important;
        }
      `;
      (document.head || document.documentElement).appendChild(style);
    }
    return true;
  }

  let attempts = 0;
  const timer = setInterval(() => {
    attempts++;
    if (applyReaderMode() || attempts >= 30) clearInterval(timer);
  }, 500);

  const observer = new MutationObserver(() => applyReaderMode());
  observer.observe(document.documentElement, { childList: true, subtree: true });
  setTimeout(() => observer.disconnect(), 20000);
})();