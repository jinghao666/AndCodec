/*
 * Part of Very Secure FTPd
 * Licence: GPL v2
 * Author: Chris Evans
 * utility.c
 */

#include "andutility.h"
#include "andsysutil.h"
#include "andstr.h"
#include "anddefs.h"
#include "andlog.h"

#define DIE_DEBUG

void
die(const char* p_text)
{
  struct mystr die_str = INIT_MYSTR;
  str_alloc_text(&die_str, "[die]");
  str_append_text(&die_str, p_text);
  enum loglevel level = LOG_ERROR;
  and_log_writeline(0, level, &die_str);
#ifdef DIE_DEBUG
  bug(p_text);
#endif
  and_sysutil_exit(1);
}

void
die2(const char* p_text1, const char* p_text2)
{
  struct mystr die_str = INIT_MYSTR;
  str_alloc_text(&die_str, p_text1);
  str_append_text(&die_str, p_text2);
  die(str_getbuf(&die_str));
}

void
bug(const char* p_text)
{
  /* Rats. Try and write the reason to the network for diagnostics */
  struct mystr bug_str = INIT_MYSTR;
  str_alloc_text(&bug_str, "OOPS: ");
  str_append_text(&bug_str, p_text);
  enum loglevel level = LOG_ERROR;
  and_log_writeline(0, level, &bug_str);
  and_sysutil_exit(1);
}

void
vsf_exit(const char* p_text)
{
  (void)p_text;
  and_sysutil_exit(0);
}

