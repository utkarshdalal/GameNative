




typedef unsigned int gn_dword;
typedef int gn_bool;
typedef unsigned long long gn_unixlib_handle_t;
typedef int (*gn_unix_call_dispatcher_t)(
    gn_unixlib_handle_t handle, unsigned int code, void *args);

extern gn_unixlib_handle_t __wine_unixlib_handle;
extern gn_unix_call_dispatcher_t __wine_unix_call_dispatcher;
extern int __wine_init_unix_call(void);

__declspec(dllexport)
int gnWineUnixCall(unsigned int code, void *args)
{
    return __wine_unix_call_dispatcher(__wine_unixlib_handle, code, args);
}

gn_bool DllMain(void *instance, gn_dword reason, void *reserved)
{
    (void)instance;
    (void)reserved;
    if (reason == 1                         )
        return __wine_init_unix_call() == 0;
    return 1;
}
