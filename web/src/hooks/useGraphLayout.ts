import { useCallback, useSyncExternalStore } from "react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { getGraphLayout, setGraphLayout, type GraphLayoutDocument } from "../api/users";
import { getSessionFamilyId, getSessionUserId } from "../api/session";
import { subscribeAuthLifecycle } from "../auth";

const SAVE_DEBOUNCE_MS = 600;
const CONTROLLER_KEY = "graphLayoutController";

type GraphLayoutState = {
  phase: "loading" | "ready" | "loadError";
  document: LayoutDocument | null;
  loadError: unknown;
  saveError: unknown;
  pending: boolean;
  saving: boolean;
};

type LayoutDocument = Omit<GraphLayoutDocument, "positions" | "collapsed"> & {
  positions: NonNullable<GraphLayoutDocument["positions"]>;
  collapsed: NonNullable<GraphLayoutDocument["collapsed"]>;
};
type LayoutUpdate = (current: LayoutDocument) => LayoutDocument;
type SaveRequest = { document: LayoutDocument; version: number };
type Listener = () => void;

const controllers = new WeakMap<QueryClient, Map<number, GraphLayoutController>>();
const subscribedClients = new WeakSet<QueryClient>();
const EMPTY_STATE: GraphLayoutState = {
  phase: "loadError",
  document: null,
  loadError: null,
  saveError: null,
  pending: false,
  saving: false,
};
const EMPTY_SUBSCRIBE = () => () => {};

function normalized(document: GraphLayoutDocument): LayoutDocument {
  return {
    mode: document.mode === "manual" ? "manual" : "auto",
    positions: document.positions ?? {},
    collapsed: document.collapsed ?? [],
  };
}

function controllerMap(queryClient: QueryClient): Map<number, GraphLayoutController> {
  let map = controllers.get(queryClient);
  if (!map) {
    map = new Map();
    controllers.set(queryClient, map);
  }
  if (!subscribedClients.has(queryClient)) {
    subscribedClients.add(queryClient);
    queryClient.getQueryCache().subscribe((event) => {
      const key = event.query.queryKey;
      if (event.type !== "removed" || key[0] !== CONTROLLER_KEY || typeof key[1] !== "number") return;
      const controller = controllers.get(queryClient)?.get(key[1]);
      controllers.get(queryClient)?.delete(key[1]);
      controller?.dispose();
    });
  }
  return map;
}

function getController(queryClient: QueryClient, userId: number): GraphLayoutController {
  const map = controllerMap(queryClient);
  let controller = map.get(userId);
  if (!controller) {
    controller = new GraphLayoutController(queryClient, userId);
    map.set(userId, controller);
    queryClient.setQueryDefaults([CONTROLLER_KEY, userId], { gcTime: Infinity });
    queryClient.setQueryData([CONTROLLER_KEY, userId], true);
  }
  return controller;
}

class GraphLayoutController {
  private readonly queryClient: QueryClient;
  private readonly userId: number;
  private readonly sessionFamilyId: string | null;
  private snapshot: GraphLayoutState = {
    phase: "loading",
    document: null,
    loadError: null,
    saveError: null,
    pending: false,
    saving: false,
  };
  private readonly listeners = new Set<Listener>();
  private loadPromise: Promise<void> | null = null;
  private timer: number | undefined;
  private queued: SaveRequest | null = null;
  private inflight: SaveRequest | null = null;
  private version = 0;
  private disposed = false;
  private cleanupTimer: number | undefined;
  private readonly unsubscribeAuth: () => void;

  constructor(queryClient: QueryClient, userId: number) {
    this.queryClient = queryClient;
    this.userId = userId;
    this.sessionFamilyId = getSessionFamilyId();
    // Login/logout and definitive 401s all publish this event. Token refresh deliberately
    // does not: rotating an access token must not discard an in-progress layout.
    this.unsubscribeAuth = subscribeAuthLifecycle(() => this.removeAnchor());
  }

  readonly getSnapshot = (): GraphLayoutState => this.snapshot;

  readonly subscribe = (listener: Listener): (() => void) => {
    window.clearTimeout(this.cleanupTimer);
    this.listeners.add(listener);
    this.load();
    return () => {
      this.listeners.delete(listener);
      this.scheduleCleanup();
    };
  };

  update(update: LayoutUpdate, debounce: boolean): void {
    if (this.disposed || this.snapshot.phase !== "ready" || !this.snapshot.document) return;
    const document = normalized(update(this.snapshot.document));
    this.version += 1;
    this.snapshot = { ...this.snapshot, document, pending: true };
    this.emit();

    // A failed queue is resumed only by Retry. Further edits still update the retained
    // document synchronously, so Retry always sends the newest state.
    if (this.snapshot.saveError) return;
    window.clearTimeout(this.timer);
    this.timer = undefined;
    if (debounce) {
      this.timer = window.setTimeout(() => {
        this.timer = undefined;
        this.enqueueLatest();
      }, SAVE_DEBOUNCE_MS);
    } else {
      this.enqueueLatest();
    }
  }

  retryLoad = (): void => {
    if (this.snapshot.phase !== "loadError") return;
    this.snapshot = { ...this.snapshot, phase: "loading", loadError: null };
    this.emit();
    this.load();
  };

  retrySave = (): void => {
    if (!this.snapshot.saveError || !this.snapshot.document) return;
    this.snapshot = { ...this.snapshot, saveError: null, pending: true };
    this.emit();
    this.enqueueLatest();
  };

  dispose(): void {
    if (this.disposed) return;
    const wakeMountedSameUser = getSessionUserId() === this.userId;
    this.disposed = true;
    window.clearTimeout(this.timer);
    window.clearTimeout(this.cleanupTimer);
    this.timer = undefined;
    this.queued = null;
    // A mounted consumer can outlive an explicit query-cache clear. Wake it so its next
    // render acquires the fresh controller that the cache-removal event installs.
    this.snapshot = { ...this.snapshot, phase: "loading", document: null, pending: false, saving: false };
    if (wakeMountedSameUser) this.emit();
    this.listeners.clear();
    this.unsubscribeAuth();
  }

  private load(): void {
    if (this.disposed || this.loadPromise || this.snapshot.phase !== "loading" || this.snapshot.document) return;
    if (!this.sameSession()) {
      this.removeAnchor();
      return;
    }
    this.loadPromise = getGraphLayout(this.userId)
      .then((document) => {
        if (this.disposed) return;
        if (!this.sameSession()) {
          this.removeAnchor();
          return;
        }
        if (this.snapshot.document) return;
        this.snapshot = {
          phase: "ready",
          document: normalized(document),
          loadError: null,
          saveError: null,
          pending: false,
          saving: false,
        };
        this.emit();
      })
      .catch((error: unknown) => {
        if (this.disposed) return;
        if (!this.sameSession()) {
          this.removeAnchor();
          return;
        }
        this.snapshot = { ...this.snapshot, phase: "loadError", loadError: error };
        this.emit();
      })
      .finally(() => {
        this.loadPromise = null;
        this.scheduleCleanup();
      });
  }

  private enqueueLatest(): void {
    if (this.disposed || !this.snapshot.document) return;
    if (!this.sameSession()) {
      this.removeAnchor();
      return;
    }
    this.queued = { document: this.snapshot.document, version: this.version };
    this.pump();
  }

  private pump(): void {
    if (this.disposed || this.inflight || !this.queued || this.snapshot.saveError) return;
    if (!this.sameSession()) {
      this.removeAnchor();
      return;
    }
    const request = this.queued;
    this.queued = null;
    this.inflight = request;
    this.snapshot = { ...this.snapshot, saving: true, pending: request.version !== this.version || !!this.timer };
    this.emit();
    setGraphLayout(this.userId, request.document)
      .then(() => {
        if (this.disposed) return;
        this.inflight = null;
        const savedLatest = request.version === this.version && !this.queued && !this.timer;
        this.snapshot = { ...this.snapshot, saving: false, pending: !savedLatest };
        this.emit();
        this.pump();
      })
      .catch((error: unknown) => {
        if (this.disposed) return;
        this.inflight = null;
        this.queued = null;
        window.clearTimeout(this.timer);
        this.timer = undefined;
        this.snapshot = { ...this.snapshot, saving: false, pending: true, saveError: error };
        this.emit();
      })
      .finally(() => this.scheduleCleanup());
  }

  private emit(): void {
    for (const listener of this.listeners) listener();
  }

  private sameSession(): boolean {
    if (getSessionUserId() !== this.userId) return false;
    // Malformed tokens occur in test scaffolding and old deployments; user isolation remains
    // enforced there. Real JWTs add the session-family boundary without coupling to refreshes.
    return this.sessionFamilyId === null || getSessionFamilyId() === this.sessionFamilyId;
  }

  private scheduleCleanup(): void {
    if (this.listeners.size > 0 || this.disposed) return;
    window.clearTimeout(this.cleanupTimer);
    this.cleanupTimer = window.setTimeout(() => {
      const settledClean = this.snapshot.phase === "ready" &&
        !this.snapshot.pending && !this.snapshot.saving && !this.snapshot.saveError &&
        !this.loadPromise && !this.timer && !this.inflight && !this.queued;
      if (this.listeners.size === 0 && settledClean) this.removeAnchor();
    }, 0);
  }

  private removeAnchor(): void {
    this.queryClient.removeQueries({ queryKey: [CONTROLLER_KEY, this.userId], exact: true });
  }
}

export function useGraphLayout(userId: number | null) {
  const queryClient = useQueryClient();
  const controller = userId == null ? null : getController(queryClient, userId);
  const state = useSyncExternalStore(
    controller?.subscribe ?? EMPTY_SUBSCRIBE,
    controller?.getSnapshot ?? (() => EMPTY_STATE),
  );
  const update = useCallback(
    (updater: LayoutUpdate, debounce = false) => controller?.update(updater, debounce),
    [controller],
  );
  return { ...state, update, retryLoad: controller?.retryLoad, retrySave: controller?.retrySave };
}
