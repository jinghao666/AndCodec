#include "andstack.h"
#include <stddef.h>
#include "andsysutil.h"
#include "andlog.h"

static int and_stack_get_pos(filesize_t pos, int size);

int and_stack_init(SimpleStack *p_stack, unsigned int data_len, unsigned int max_num)
{
	//and_log_writeline_simple(0, LOG_DEBUG, "stack init");

	if(!p_stack) {
		and_log_writeline_simple(0, LOG_ERROR, "stack not allocated");
		return -1;
	}

	p_stack->size		= max_num;
	p_stack->pos		= 0;
	p_stack->data_len	= data_len;

	p_stack->array = (void *)and_sysutil_malloc( data_len * max_num );
	if(!p_stack->array) {
		and_log_writeline_simple(0, LOG_DEBUG, "failed to alloc stack data"); 
		return -1;
	}
	and_sysutil_memclr(p_stack->array, data_len * max_num);

	and_log_writeline_easy(0, LOG_INFO, "stack inited, every %d byte for %d",
		data_len, max_num);
	return 0;
}
	
int and_stack_push(SimpleStack *p_stack, void *p_data)
{
	if (!p_stack) {
		and_log_writeline_simple(0, LOG_ERROR, "stack not allocated");
		return -1;
	}

	if (!p_data) {
		and_log_writeline_simple(0, LOG_ERROR, "push null data");
		return -1;
	}

	const char *p_str = (char *)p_data;
	and_log_writeline_easy(0, LOG_DEBUG, "stack push 0x%02x 0x%02x", p_str[0], p_str[1]); 

	if (p_stack->pos >= p_stack->size) {
		and_log_writeline_easy(0, LOG_ERROR, "stack overflowed %d.%d", 
			p_stack->pos, p_stack->size);
		return -1;
	}
	
	int pos = and_stack_get_pos(p_stack->abs_w_pos, p_stack->size);
	//p_stack->array[pos] = data;
	int offset = p_stack->data_len * pos;
	and_sysutil_memcpy(p_stack->array + offset, p_data, p_stack->data_len);
	p_stack->abs_w_pos++;
	return 0;
}

int and_stack_pop(SimpleStack *p_stack, void *p_data)
{
	//and_log_writeline_simple(0, LOG_DEBUG, "stack pop");

	if (!p_stack) {
		and_log_writeline_simple(0, LOG_ERROR, "stack not allocated");
		return -1;
	}

	if (p_stack->pos < 1) {
		and_log_writeline_easy(0, LOG_ERROR, "stack underflowed %d.%d", 
			p_stack->pos, p_stack->size);
		return -1;
	}
	
	p_stack->pos--;
	unsigned int offset = p_stack->data_len * p_stack->pos;
	and_sysutil_memcpy(p_data, p_stack->array + offset, p_stack->data_len);
	p_stack->abs_r_pos++;

	const char *p_str = (char *)p_data;
	and_log_writeline_easy(0, LOG_DEBUG, "stack pop 0x%02x 0x%02x", p_str[0], p_str[1]); 
	return 0;
}

void and_stack_close(SimpleStack *p_stack)
{
	//and_log_writeline_simple(0, LOG_DEBUG, "stack close");

	if(!p_stack) {
		and_log_writeline_simple(0, LOG_ERROR, "stack not allocated");
		return;
	}

	if(p_stack->array) {
		and_sysutil_free(p_stack->array);
		p_stack->array = NULL;
	}

	and_log_writeline_simple(0, LOG_INFO, "stack closed.");
}

static int and_stack_get_pos(filesize_t pos, int size)
{
	return pos % size;
}

