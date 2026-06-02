// gamenative pack:rmmv YEP_CoreEngine noop stub
// minimal scaffold: declare Imported.YEP_CoreEngine = true so games that check
// `if (Imported.YEP_CoreEngine)` don't crash. v1 is intentionally thin;
// real method-level stubbing lands per-title if field-test surfaces crashes.
var Imported = Imported || {};
Imported.YEP_CoreEngine = true;
Imported.YEP_CoreEngineVersion = 1.32;

var Yanfly = Yanfly || {};
Yanfly.Param = Yanfly.Param || {};
Yanfly.Core = Yanfly.Core || {};
Yanfly.Util = Yanfly.Util || {};
Yanfly.Icon = Yanfly.Icon || {};

if (self.__gnShimVerbose) try { console.log('gamenative rmmv-plugin stub: YEP_CoreEngine loaded'); } catch (e) {}
