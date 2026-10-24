(() => {
    'use strict';

    if (window.FoliaTvRemote && window.FoliaTvRemote.version === 1) {
        window.FoliaTvRemote.focusInitial();
        return;
    }

    const FOCUSABLE = [
        'a[href]',
        'button:not([disabled])',
        'input:not([disabled]):not([type="hidden"])',
        'select:not([disabled])',
        'textarea:not([disabled])',
        '[tabindex]:not([tabindex="-1"])',
        '[role="button"]:not([aria-disabled="true"])',
        '[role="menuitem"]:not([aria-disabled="true"])',
        '[role="option"]:not([aria-disabled="true"])',
        '[role="slider"]:not([aria-disabled="true"])',
        '[contenteditable="true"]',
    ].join(',');

    const nativeBridge = window.FoliaTvBridge || null;
    let lastFocused = null;

    const dispatchKey = (key, options = {}) => {
        const event = new KeyboardEvent('keydown', {
            key,
            code: options.code || key,
            bubbles: true,
            cancelable: true,
            composed: true,
            ctrlKey: Boolean(options.ctrlKey),
            shiftKey: Boolean(options.shiftKey),
            altKey: Boolean(options.altKey),
            metaKey: Boolean(options.metaKey),
        });
        const target = document.activeElement && document.activeElement !== document.body
            ? document.activeElement
            : document;
        target.dispatchEvent(event);
        return event;
    };

    const isTextEntry = element => {
        if (!(element instanceof HTMLElement)) return false;
        const tag = element.tagName.toLowerCase();
        return tag === 'input'
            || tag === 'textarea'
            || tag === 'select'
            || element.isContentEditable
            || element.matches('[role="combobox"][aria-expanded="true"]');
    };

    const isVisible = element => {
        if (!(element instanceof HTMLElement)) return false;
        if (element.closest('[inert], [aria-hidden="true"]')) return false;
        const style = getComputedStyle(element);
        if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) {
            return false;
        }
        const rect = element.getBoundingClientRect();
        return rect.width > 2 && rect.height > 2
            && rect.bottom > 0 && rect.right > 0
            && rect.top < innerHeight && rect.left < innerWidth;
    };

    const activeScope = () => {
        const windows = Array.from(document.querySelectorAll(
            '[data-folia-sheet="true"], [data-folia-keyboard-window="true"], [role="dialog"], [aria-modal="true"]',
        )).filter(isVisible);
        return windows.length ? windows[windows.length - 1] : document;
    };

    const focusables = () => Array.from(activeScope().querySelectorAll(FOCUSABLE))
        .filter(isVisible);

    const centerOf = element => {
        const rect = element.getBoundingClientRect();
        return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    };

    const directionScore = (from, to, direction) => {
        const dx = to.x - from.x;
        const dy = to.y - from.y;
        if (direction === 'left' && dx >= -2) return null;
        if (direction === 'right' && dx <= 2) return null;
        if (direction === 'up' && dy >= -2) return null;
        if (direction === 'down' && dy <= 2) return null;

        const horizontal = direction === 'left' || direction === 'right';
        const primary = horizontal ? Math.abs(dx) : Math.abs(dy);
        const cross = horizontal ? Math.abs(dy) : Math.abs(dx);
        return primary + cross * 2.4;
    };

    const focusElement = element => {
        if (!(element instanceof HTMLElement)) return false;
        lastFocused = element;
        element.focus({ preventScroll: true });
        element.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        return true;
    };

    const navigate = direction => {
        const items = focusables();
        if (!items.length) return false;
        let current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        if (!current || !items.includes(current)) {
            current = lastFocused && items.includes(lastFocused) ? lastFocused : items[0];
            if (!current || current === document.activeElement) return focusElement(items[0]);
        }

        const from = centerOf(current);
        let best = null;
        let bestScore = Number.POSITIVE_INFINITY;
        for (const item of items) {
            if (item === current) continue;
            const score = directionScore(from, centerOf(item), direction);
            if (score !== null && score < bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return best ? focusElement(best) : true;
    };

    const focusInitial = () => {
        const current = document.activeElement;
        if (current instanceof HTMLElement && current !== document.body && isVisible(current)) {
            if (isTextEntry(current) && nativeBridge) nativeBridge.setInputActive(true);
            return;
        }
        const items = focusables();
        if (items.length) focusElement(items[0]);
    };

    const dispatchRemoteKey = (key, modifiers = 0) => {
        const options = {
            ctrlKey: Boolean(modifiers & 1),
            shiftKey: Boolean(modifiers & 2),
            altKey: Boolean(modifiers & 4),
            metaKey: Boolean(modifiers & 8),
        };
        const event = dispatchKey(key, options);
        if (key.startsWith('Arrow') && !event.defaultPrevented) {
            const direction = key.slice(5).toLowerCase();
            navigate(direction);
        }
    };

    const mediaAction = action => {
        if (action === 'playPause') {
            dispatchKey(' ', { code: 'Space' });
        } else if (action === 'next') {
            dispatchKey('ArrowRight', { ctrlKey: true });
        } else if (action === 'previous') {
            dispatchKey('ArrowLeft', { ctrlKey: true });
        } else if (action === 'seekForward') {
            dispatchKey('ArrowRight');
        } else if (action === 'seekBackward') {
            dispatchKey('ArrowLeft');
        } else if (action === 'menu') {
            dispatchKey('Escape');
        }
    };

    const handleBack = () => {
        const event = dispatchKey('Escape');
        return event.defaultPrevented;
    };

    document.addEventListener('focusin', event => {
        lastFocused = event.target instanceof HTMLElement ? event.target : lastFocused;
        if (nativeBridge) nativeBridge.setInputActive(isTextEntry(event.target));
    }, true);

    document.addEventListener('click', event => {
        if (event.target instanceof HTMLElement) lastFocused = event.target;
    }, true);

    const style = document.createElement('style');
    style.id = 'folia-tv-remote-style';
    style.textContent = `
        html[data-folia-tv="true"] :focus {
            outline: 3px solid rgba(232, 121, 249, 0.95) !important;
            outline-offset: 3px !important;
        }
        html[data-folia-tv="true"] [role="slider"]:focus {
            box-shadow: 0 0 0 4px rgba(232, 121, 249, 0.35) !important;
        }
    `;
    (document.head || document.documentElement).appendChild(style);
    document.documentElement.dataset.foliaTv = 'true';

    window.FoliaTvRemote = {
        version: 1,
        dispatchRemoteKey,
        mediaAction,
        handleBack,
        focusInitial,
        navigate,
    };

    window.addEventListener('load', () => setTimeout(focusInitial, 250), { once: true });
})();
