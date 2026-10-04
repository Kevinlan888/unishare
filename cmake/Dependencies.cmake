# Dependency checks are invoked by the first implementation that consumes them.
# Protocol codec (Task 3) requires Protobuf; secure identity/TLS (Task 4/5)
# requires OpenSSL. The header-only contract baseline requires neither.
macro(unishare_require_openssl)
 find_package(OpenSSL 3 QUIET)
 if(NOT OpenSSL_FOUND)
  message(FATAL_ERROR "UniShare requires OpenSSL >= 3. Install it and set OPENSSL_ROOT_DIR or CMAKE_PREFIX_PATH; dependencies are never downloaded automatically.")
 endif()
endmacro()
macro(unishare_require_protobuf)
 find_package(Protobuf 3.21 QUIET)
 if(NOT Protobuf_FOUND)
  message(FATAL_ERROR "UniShare requires Protobuf >= 3.21. Install it and set Protobuf_ROOT or CMAKE_PREFIX_PATH; dependencies are never downloaded automatically.")
 endif()
endmacro()

# FindOpenSSL may expose Windows import libraries without runtime DLL metadata.
# Stage the matching prefix's DLLs next to tests so CTest does not require PATH.
function(unishare_stage_openssl_runtime target)
 if(WIN32)
  file(GLOB openssl_runtime_dlls CONFIGURE_DEPENDS
   "${OPENSSL_ROOT_DIR}/bin/libcrypto-3*.dll" "${OPENSSL_ROOT_DIR}/bin/libssl-3*.dll"
   "${OPENSSL_INCLUDE_DIR}/../bin/libcrypto-3*.dll" "${OPENSSL_INCLUDE_DIR}/../bin/libssl-3*.dll")
  list(REMOVE_DUPLICATES openssl_runtime_dlls)
  foreach(runtime_dll IN LISTS openssl_runtime_dlls)
   add_custom_command(TARGET ${target} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "${runtime_dll}" "$<TARGET_FILE_DIR:${target}>"
    VERBATIM)
  endforeach()
 endif()
endfunction()
