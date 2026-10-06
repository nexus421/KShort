"use strict";

// Copy buttons: <button data-copy="https://...">
document.addEventListener("click", function (event) {
  var button = event.target.closest ? event.target.closest("[data-copy]") : null;
  if (!button || !navigator.clipboard) return;
  navigator.clipboard.writeText(button.getAttribute("data-copy")).then(function () {
    var previous = button.textContent;
    button.textContent = "Kopiert ✓";
    setTimeout(function () { button.textContent = previous; }, 1500);
  });
});

// Confirmation before destructive forms: <form data-confirm="Really?">
document.addEventListener("submit", function (event) {
  var message = event.target.getAttribute("data-confirm");
  if (message && !window.confirm(message)) event.preventDefault();
});
