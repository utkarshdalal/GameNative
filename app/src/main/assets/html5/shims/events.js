// Node-compatible EventEmitter for require('events'). node-style Steam SDK wrappers in NW.js games (e.g. Steam4C2.js)
// inherit from it at script load and throw without it.
//
// load order: AFTER require-dispatcher, BEFORE pack/game scripts.
(function () {
    'use strict';
    if (!window.require || typeof window.require.register !== 'function') {
        try { console.warn('gamenative events shim: require-dispatcher missing'); } catch (e) {}
        return;
    }

    function EventEmitter() {
        if (!this._events) this._events = {};
    }
    EventEmitter.defaultMaxListeners = 10;

    EventEmitter.prototype.on = function (event, fn) {
        if (typeof fn !== 'function') return this;
        if (!this._events) this._events = {};
        (this._events[event] = this._events[event] || []).push(fn);
        return this;
    };
    EventEmitter.prototype.addListener = EventEmitter.prototype.on;

    EventEmitter.prototype.once = function (event, fn) {
        if (typeof fn !== 'function') return this;
        var self = this;
        function wrapper() {
            self.removeListener(event, wrapper);
            fn.apply(self, arguments);
        }
        wrapper._original = fn;
        return self.on(event, wrapper);
    };

    EventEmitter.prototype.emit = function (event /* , ...args */) {
        if (!this._events) return false;
        var arr = this._events[event];
        if (!arr || !arr.length) return false;
        var args = Array.prototype.slice.call(arguments, 1);
        // copy before iterating: listeners may removeListener during dispatch.
        var copy = arr.slice();
        for (var i = 0; i < copy.length; i++) {
            try {
                copy[i].apply(this, args);
            } catch (e) {
                try { console.warn('gamenative events shim: listener for "' + event + '" threw', e); } catch (_) {}
            }
        }
        return true;
    };

    EventEmitter.prototype.removeListener = function (event, fn) {
        if (!this._events) return this;
        var arr = this._events[event];
        if (!arr) return this;
        for (var i = arr.length - 1; i >= 0; i--) {
            if (arr[i] === fn || arr[i]._original === fn) arr.splice(i, 1);
        }
        if (!arr.length) delete this._events[event];
        return this;
    };
    EventEmitter.prototype.off = EventEmitter.prototype.removeListener;

    EventEmitter.prototype.removeAllListeners = function (event) {
        if (!this._events) return this;
        if (event === undefined) this._events = {};
        else delete this._events[event];
        return this;
    };

    EventEmitter.prototype.listeners = function (event) {
        var arr = this._events && this._events[event];
        return arr ? arr.slice() : [];
    };
    EventEmitter.prototype.listenerCount = function (event) {
        var arr = this._events && this._events[event];
        return arr ? arr.length : 0;
    };
    EventEmitter.prototype.eventNames = function () {
        return this._events ? Object.keys(this._events) : [];
    };
    EventEmitter.prototype.setMaxListeners = function () { return this; };
    EventEmitter.prototype.getMaxListeners = function () { return EventEmitter.defaultMaxListeners; };

    // node convention: `require('events')` and `require('events').EventEmitter` are the same constructor.
    EventEmitter.EventEmitter = EventEmitter;

    window.require.register('events', EventEmitter);

    if (self.__gnShimVerbose) {
        try { console.log('gamenative events shim installed'); } catch (e) {}
    }
})();
