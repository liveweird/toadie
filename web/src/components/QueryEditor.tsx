import { useEffect, useRef } from "react";
import { useComputedColorScheme } from "@mantine/core";
import { Compartment, EditorState } from "@codemirror/state";
import { EditorView, keymap, placeholder as placeholderExtension } from "@codemirror/view";
import { defaultHighlightStyle, StreamLanguage, syntaxHighlighting } from "@codemirror/language";
import { autocompletion, completionKeymap } from "@codemirror/autocomplete";
import { setDiagnostics } from "@codemirror/lint";
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import type { EntityQueryDiagnostic } from "../api/entities";
import { cypherStream } from "../utils/queryLanguage";
import { queryCompletions, type QueryCompletionSchema } from "../utils/queryCompletion";
import { toLintDiagnostics } from "../utils/queryDiagnostics";

/**
 * The thin CodeMirror 6 wrapper behind the entity query bar (phase 7, v2.0.0 —
 * `.claude/docs/entity-query-language.md`): one `EditorView` created ONCE, over
 * `StreamLanguage.define(cypherStream)` for highlighting, `autocompletion` wired to
 * `queryCompletions`, and `@codemirror/lint`'s diagnostic markers driven by the `diagnostics`
 * prop. Every prop that can change on every render (`onChange`/`onRun`/`completionSchema`)
 * travels through a ref so the view is never torn down and rebuilt — only `value`,
 * `diagnostics`, and the computed colour scheme resync an existing view via effects.
 */
export default function QueryEditor({
  value,
  onChange,
  onRun,
  diagnostics,
  completionSchema,
  ariaLabel,
  placeholder,
}: {
  value: string;
  onChange: (value: string) => void;
  onRun: () => void;
  diagnostics: EntityQueryDiagnostic[];
  completionSchema: QueryCompletionSchema;
  ariaLabel: string;
  placeholder?: string;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const onRunRef = useRef(onRun);
  const schemaRef = useRef(completionSchema);
  const themeCompartmentRef = useRef(new Compartment());
  const colorScheme = useComputedColorScheme("light");

  // Refs are synced via an effect (never assigned during render) so the always-current
  // callbacks/schema are readable from the long-lived extensions the mount effect below
  // builds exactly once.
  useEffect(() => {
    onChangeRef.current = onChange;
    onRunRef.current = onRun;
    schemaRef.current = completionSchema;
  }, [onChange, onRun, completionSchema]);

  useEffect(() => {
    const themeCompartment = themeCompartmentRef.current;
    const view = new EditorView({
      state: EditorState.create({
        doc: value,
        extensions: [
          StreamLanguage.define(cypherStream),
          syntaxHighlighting(defaultHighlightStyle),
          history(),
          keymap.of([
            { key: "Mod-Enter", run: () => (onRunRef.current(), true) },
            ...completionKeymap,
            ...historyKeymap,
            ...defaultKeymap,
          ]),
          autocompletion({
            override: [(context) => queryCompletions(context.state.sliceDoc(0, context.pos), schemaRef.current)],
          }),
          placeholderExtension(placeholder ?? ""),
          EditorView.contentAttributes.of({ "aria-label": ariaLabel, "aria-multiline": "true" }),
          themeCompartment.of(EditorView.theme({}, { dark: colorScheme === "dark" })),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) onChangeRef.current(update.state.doc.toString());
          }),
        ],
      }),
      parent: containerRef.current ?? undefined,
    });
    viewRef.current = view;
    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // Created exactly once: value/diagnostics/theme resync via their own effects below, and
    // onChange/onRun/completionSchema travel through refs — rebuilding the view on every
    // render would drop cursor position, undo history, and in-flight autocompletion.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const view = viewRef.current;
    if (view && view.state.doc.toString() !== value) {
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
    }
  }, [value]);

  useEffect(() => {
    const view = viewRef.current;
    if (view) view.dispatch(setDiagnostics(view.state, toLintDiagnostics(diagnostics, value)));
  }, [diagnostics, value]);

  useEffect(() => {
    const view = viewRef.current;
    if (view) {
      view.dispatch({
        effects: themeCompartmentRef.current.reconfigure(EditorView.theme({}, { dark: colorScheme === "dark" })),
      });
    }
  }, [colorScheme]);

  return <div ref={containerRef} />;
}
